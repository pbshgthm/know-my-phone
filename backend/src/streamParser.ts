/**
 * Incrementally extracts the "answer" field value from streaming JSON output.
 * Splits into sentences for per-sentence TTS streaming.
 */

const enum ParserState {
  /** Scanning for the "answer" key */
  SCANNING,
  /** Inside the answer string value, accumulating text */
  IN_STRING,
  /** Done — closing quote found */
  DONE,
}

// Sentence boundary: .!? followed by space, quote, or end of input.
// Min 20 chars to avoid false splits on abbreviations like "Dr." or "U.S."
const MIN_SENTENCE_LENGTH = 20;

export class StreamingAnswerParser {
  private state: ParserState = ParserState.SCANNING;
  private rawOutput = "";
  private answerBuffer = "";
  private escaped = false;

  // Track how far into finding `"answer"` + `:` + `"` we are
  private scanBuffer = "";

  /**
   * Feed a chunk of LLM output. Returns any complete sentences extracted.
   */
  feed(chunk: string): string[] {
    this.rawOutput += chunk;

    if (this.state === ParserState.DONE) {
      return [];
    }

    const sentences: string[] = [];

    for (const ch of chunk) {
      switch (this.state) {
        case ParserState.SCANNING:
          this.scanBuffer += ch;
          // Look for "answer" followed by optional whitespace, colon, optional whitespace, opening quote
          // We search for the pattern in the accumulated scan buffer
          if (this.tryScanForAnswerStart()) {
            this.state = ParserState.IN_STRING;
            this.scanBuffer = "";
          }
          break;

        case ParserState.IN_STRING:
          if (this.escaped) {
            // Handle escape sequences
            switch (ch) {
              case '"':
                this.answerBuffer += '"';
                break;
              case '\\':
                this.answerBuffer += '\\';
                break;
              case 'n':
                this.answerBuffer += '\n';
                break;
              case 'r':
                this.answerBuffer += '\r';
                break;
              case 't':
                this.answerBuffer += '\t';
                break;
              default:
                this.answerBuffer += ch;
                break;
            }
            this.escaped = false;
          } else if (ch === '\\') {
            this.escaped = true;
          } else if (ch === '"') {
            // End of answer string
            this.state = ParserState.DONE;
          } else {
            this.answerBuffer += ch;
            // Check for sentence boundary
            const extracted = this.extractSentences();
            sentences.push(...extracted);
          }
          break;

        case ParserState.DONE:
          break;
      }
    }

    return sentences;
  }

  /**
   * Returns any remaining text that didn't form a complete sentence.
   */
  flush(): string {
    const remaining = this.answerBuffer.trim();
    this.answerBuffer = "";
    return remaining;
  }

  /**
   * Returns the full raw LLM output for final JSON parsing.
   */
  getRawOutput(): string {
    return this.rawOutput;
  }

  /**
   * Check if we've found the "answer" key start pattern in scanBuffer.
   * Matches: "answer" : "  (with flexible whitespace)
   */
  private tryScanForAnswerStart(): boolean {
    // Use regex to find the pattern anywhere in the scan buffer
    const match = this.scanBuffer.match(/"answer"\s*:\s*"/);
    if (match) {
      // Check if the match is at the end of the buffer (the opening quote is the last char)
      const matchEnd = match.index! + match[0].length;
      if (matchEnd === this.scanBuffer.length) {
        return true;
      }
      // If match is in the middle, we already passed the opening quote.
      // Reset and re-scan from after the match.
      // This shouldn't happen in well-formed JSON from LLM, but handle it.
    }
    return false;
  }

  /**
   * Extract complete sentences from answerBuffer.
   * A sentence ends with .!? followed by a space or end-of-buffer,
   * and must be at least MIN_SENTENCE_LENGTH chars.
   */
  private extractSentences(): string[] {
    const sentences: string[] = [];

    while (true) {
      // Find a sentence boundary: .!? followed by space
      let splitIdx = -1;
      for (let i = MIN_SENTENCE_LENGTH - 1; i < this.answerBuffer.length - 1; i++) {
        const ch = this.answerBuffer[i];
        const next = this.answerBuffer[i + 1];
        if ((ch === '.' || ch === '!' || ch === '?') && (next === ' ' || next === '\n')) {
          splitIdx = i + 1;
          break;
        }
      }

      if (splitIdx === -1) break;

      const sentence = this.answerBuffer.slice(0, splitIdx).trim();
      this.answerBuffer = this.answerBuffer.slice(splitIdx).trimStart();

      if (sentence.length > 0) {
        sentences.push(sentence);
      }
    }

    return sentences;
  }
}

/**
 * Strip audio tags like [warmly], [cheerfully] from text.
 */
export function stripAudioTags(text: string): string {
  return text.replace(/\[[\w\s]+\]\s*/g, "").trim();
}
