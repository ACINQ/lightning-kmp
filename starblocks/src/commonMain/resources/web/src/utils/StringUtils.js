export function isBlank (string) {
  return (string === undefined || string === null || string.trim().length === 0)
}
export function isNull (string) {
  return (string === undefined || string === null)
}
