/**
 * Incrementally extracts the "answer" field value from streaming JSON output.
 * Also detects and extracts the "highlights" array early (before answer finishes).
 * Splits answer into sentences for per-sentence TTS streaming.
 */

const enum AnswerParseState {
  /** Scanning for the "answer" key */
  SCANNING,
  /** Inside the answer string value, accumulating text */
  IN_STRING,
  /** Done — closing quote found */
  DONE,
}

const enum HighlightParseState {
  /** Scanning for the "highlights" key */
  SCANNING,
  /** Inside the highlights array, tracking bracket depth */
  IN_ARRAY,
  /** Done — closing bracket found */
  DONE,
}

// Sentence boundary: .!? followed by space, quote, or end of input.
// Min 20 chars to avoid false splits on abbreviations like "Dr." or "U.S."
const MIN_SENTENCE_LENGTH = 20;

export class StreamingAnswerParser {
  // Answer parsing state
  private answerState: AnswerParseState = AnswerParseState.SCANNING;
  private rawOutput = "";
  private answerBuffer = "";
  private answerEscaped = false;
  private answerScanBuffer = "";

  // Highlight parsing state (runs in parallel on same character stream)
  private highlightState: HighlightParseState = HighlightParseState.SCANNING;
  private highlightScanBuffer = "";
  private highlightArrayContent = "";
  private highlightBracketDepth = 0;
  private highlightInString = false;
  private highlightStringEscaped = false;
  private _earlyHighlights: Array<{ elementId: string; label: string }> | null = null;

  /**
   * Feed a chunk of LLM output. Returns any complete sentences extracted.
   */
  feed(chunk: string): string[] {
    this.rawOutput += chunk;

    const sentences: string[] = [];

    for (const ch of chunk) {
      this.processAnswerChar(ch, sentences);
      this.processHighlightChar(ch);
    }

    return sentences;
  }

  private processAnswerChar(ch: string, sentences: string[]): void {
    switch (this.answerState) {
      case AnswerParseState.SCANNING:
        this.answerScanBuffer += ch;
        if (this.tryScanForAnswerStart()) {
          this.answerState = AnswerParseState.IN_STRING;
          this.answerScanBuffer = "";
        }
        break;

      case AnswerParseState.IN_STRING:
        if (this.answerEscaped) {
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
          this.answerEscaped = false;
        } else if (ch === '\\') {
          this.answerEscaped = true;
        } else if (ch === '"') {
          this.answerState = AnswerParseState.DONE;
        } else {
          this.answerBuffer += ch;
          const extracted = this.extractSentences();
          sentences.push(...extracted);
        }
        break;

      case AnswerParseState.DONE:
        break;
    }
  }

  private processHighlightChar(ch: string): void {
    switch (this.highlightState) {
      case HighlightParseState.SCANNING:
        this.highlightScanBuffer += ch;
        if (this.tryScanForHighlightsStart()) {
          this.highlightState = HighlightParseState.IN_ARRAY;
          this.highlightBracketDepth = 1;
          this.highlightArrayContent = "[";
          this.highlightScanBuffer = "";
        }
        break;

      case HighlightParseState.IN_ARRAY:
        this.highlightArrayContent += ch;

        if (this.highlightInString) {
          if (this.highlightStringEscaped) {
            this.highlightStringEscaped = false;
          } else if (ch === '\\') {
            this.highlightStringEscaped = true;
          } else if (ch === '"') {
            this.highlightInString = false;
          }
          return;
        }

        if (ch === '"') {
          this.highlightInString = true;
        } else if (ch === '[') {
          this.highlightBracketDepth++;
        } else if (ch === ']') {
          this.highlightBracketDepth--;
          if (this.highlightBracketDepth === 0) {
            this.highlightState = HighlightParseState.DONE;
            try {
              this._earlyHighlights = JSON.parse(this.highlightArrayContent);
            } catch {
              this._earlyHighlights = null;
            }
          }
        }
        break;

      case HighlightParseState.DONE:
        break;
    }
  }

  /**
   * Returns early-parsed highlights, or null if not yet available.
   * Non-null means the highlights array has been fully parsed from the stream.
   */
  getEarlyHighlights(): Array<{ elementId: string; label: string }> | null {
    return this._earlyHighlights;
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
   * Check if we've found the "answer" key start pattern in answerScanBuffer.
   * Matches: "answer" : "  (with flexible whitespace)
   */
  private tryScanForAnswerStart(): boolean {
    const match = this.answerScanBuffer.match(/"answer"\s*:\s*"/);
    if (match) {
      const matchEnd = match.index! + match[0].length;
      if (matchEnd === this.answerScanBuffer.length) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if we've found the "highlights" key start pattern.
   * Matches: "highlights" : [  (with flexible whitespace)
   */
  private tryScanForHighlightsStart(): boolean {
    const match = this.highlightScanBuffer.match(/"highlights"\s*:\s*\[/);
    if (match) {
      const matchEnd = match.index! + match[0].length;
      if (matchEnd === this.highlightScanBuffer.length) {
        return true;
      }
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
