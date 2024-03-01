export function formatAmount (num) {
  if (!Intl.NumberFormat) return num
  const moneyFormat = new Intl.NumberFormat('en', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 8,
  })
  return moneyFormat.format(num)
}
