export const pathCache = new Map<string, string>();
let pathIdCounter = 0;

export function getPathId(fullPath: string): string {
  for (const [id, p] of pathCache.entries()) {
    if (p === fullPath) return id;
  }
  const id = (++pathIdCounter).toString(36);
  pathCache.set(id, fullPath);
  if (pathCache.size > 2000) {
    const firstKey = pathCache.keys().next().value;
    if (firstKey) pathCache.delete(firstKey);
  }
  return id;
}
