import { memo, useState, useMemo, useRef, useEffect, useCallback } from "react";
import { IconCopy, IconCheck } from "./Icons";

/**
 * Split an HTML string into alternating segments of "non-pre" and "pre" blocks.
 * Returns an array of { type: 'html' | 'pre', content: string }.
 */
interface Segment {
  type: "html" | "pre";
  content: string; // for html: raw html string; for pre: code text
  rawHtml: string; // original html for re-rendering
}

function splitPreBlocks(html: string): Segment[] {
  const segments: Segment[] = [];
  // Match <pre> blocks (possibly containing <code>)
  const preRegex = /<pre[^>]*>([\s\S]*?)<\/pre>/gi;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = preRegex.exec(html)) !== null) {
    // Add preceding non-pre html
    if (match.index > lastIndex) {
      const htmlBefore = html.slice(lastIndex, match.index);
      if (htmlBefore.trim()) {
        segments.push({
          type: "html",
          content: htmlBefore,
          rawHtml: htmlBefore,
        });
      }
    }

    // Extract text content from the <pre> block (strip HTML tags for copy)
    const innerHtml = match[1];
    const textContent = innerHtml.replace(/<[^>]*>/g, "");
    segments.push({
      type: "pre",
      content: textContent,
      rawHtml: match[0],
    });
    lastIndex = match.index + match[0].length;
  }

  // Add trailing html
  if (lastIndex < html.length) {
    const trailing = html.slice(lastIndex);
    if (trailing.trim()) {
      segments.push({ type: "html", content: trailing, rawHtml: trailing });
    }
  }

  return segments;
}

/** Copy button for code blocks */
function CodeCopyBtn({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="code-copy-btn"
      title="Copy code"
      onClick={(e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(text).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? <IconCheck size={13} /> : <IconCopy size={13} />}
    </button>
  );
}

/** Inline annotation input for a single segment */
function AnnotationInput({
  segIndex,
  value,
  onChange,
  onClose,
}: {
  segIndex: number;
  value: string;
  onChange: (segIndex: number, text: string) => void;
  onClose: () => void;
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    // Auto-focus when opened
    textareaRef.current?.focus();
  }, []);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(segIndex, e.target.value);
    },
    [segIndex, onChange],
  );

  const handleClear = useCallback(() => {
    onChange(segIndex, "");
    onClose();
  }, [segIndex, onChange, onClose]);

  return (
    <div className="annotation-input-wrapper">
      <textarea
        ref={textareaRef}
        className="annotation-input"
        placeholder="Add a comment on this section…"
        value={value}
        onChange={handleChange}
        rows={2}
      />
      <button
        className="annotation-clear-btn"
        title="Clear and close"
        onClick={handleClear}
      >
        ✕
      </button>
    </div>
  );
}

/** Annotatable wrapper for a single segment */
function AnnotatableSegment({
  segIndex,
  annotation,
  onAnnotationChange,
  children,
}: {
  segIndex: number;
  annotation: string;
  onAnnotationChange: (segIndex: number, text: string) => void;
  children: React.ReactNode;
}) {
  const [isOpen, setIsOpen] = useState(false);
  const hasAnnotation = annotation.trim().length > 0;

  const toggleInput = useCallback(() => {
    setIsOpen((prev) => !prev);
  }, []);

  const handleClose = useCallback(() => {
    setIsOpen(false);
  }, []);

  return (
    <div
      className={`annotatable-segment${hasAnnotation ? " annotated-segment" : ""}`}
    >
      {children}
      <button
        className={`annotation-trigger${isOpen ? " active" : ""}`}
        title="Add comment"
        onClick={toggleInput}
      >
        💬
      </button>
      {isOpen && (
        <AnnotationInput
          segIndex={segIndex}
          value={annotation}
          onChange={onAnnotationChange}
          onClose={handleClose}
        />
      )}
      {!isOpen && hasAnnotation && (
        <div
          className="annotation-preview"
          onClick={toggleInput}
          title="Click to edit"
        >
          <span className="annotation-preview-icon">💬</span>
          <span className="annotation-preview-text">{annotation}</span>
        </div>
      )}
    </div>
  );
}

interface MarkdownContentProps {
  html: string;
  /** If true, skip copy buttons on pre blocks (e.g. inside step cards) */
  skipCopyButtons?: boolean;
  /** If true, show annotation controls on each segment */
  annotatable?: boolean;
  /** Map of segment index → comment text */
  annotations?: Record<number, string>;
  /** Called when a segment's annotation changes */
  onAnnotationChange?: (segmentIndex: number, text: string) => void;
}

/**
 * Renders markdown HTML with React-managed copy buttons on code blocks.
 * Replaces the old DOM injection approach.
 */
export const MarkdownContent = memo(function MarkdownContent({
  html,
  skipCopyButtons = false,
  annotatable = false,
  annotations = {},
  onAnnotationChange,
}: MarkdownContentProps) {
  const segments = useMemo(() => splitPreBlocks(html), [html]);

  // Fast path: no <pre> blocks and not annotatable, just render as-is
  if (
    !annotatable &&
    segments.length <= 1 &&
    segments[0]?.type === "html"
  ) {
    return <div dangerouslySetInnerHTML={{ __html: html }} />;
  }

  const wrapWithAnnotation = (
    segIndex: number,
    node: React.ReactNode,
  ): React.ReactNode => {
    if (!annotatable || !onAnnotationChange) return node;
    return (
      <AnnotatableSegment
        key={segIndex}
        segIndex={segIndex}
        annotation={annotations[segIndex] ?? ""}
        onAnnotationChange={onAnnotationChange}
      >
        {node}
      </AnnotatableSegment>
    );
  };

  return (
    <div>
      {segments.map((seg, i) => {
        if (seg.type === "html") {
          const htmlNode = (
            <div
              key={i}
              dangerouslySetInnerHTML={{ __html: seg.content }}
            />
          );
          return wrapWithAnnotation(i, htmlNode);
        }
        // Pre block: render with copy button
        const preNode = (
          <div key={i} style={{ position: "relative" }}>
            <pre
              dangerouslySetInnerHTML={{
                __html: seg.rawHtml.replace(/^<pre[^>]*>|<\/pre>$/gi, ""),
              }}
            />
            {!skipCopyButtons && <CodeCopyBtn text={seg.content} />}
          </div>
        );
        return wrapWithAnnotation(i, preNode);
      })}
    </div>
  );
});
