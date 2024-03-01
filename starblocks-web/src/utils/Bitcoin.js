import debug from '@/utils/Debug'
const log = debug('starblocks:bitcoin')

/**
 * convert amount to the given bitcoin unit
 * @param {*} amount amount in satoshi
 * @param {*} unit bitcoin unit, can be 'msat', 'sat', 'mbtc', 'btc'. If unit is not one of these, will return amount
 */
export function convertSatToUnit (amount, unit) {
  if (isNaN(amount) || unit == null) return 0
  log('amount: %s unit: %s', amount, unit.code)
  if (btcUnits.has(unit)) {
    return amount / (btcUnits.get(unit).value_sat)
  } else {
    return amount
  }
}

export const btcUnits = ((new Map())
  .set('msat',  { code:'msat',  label: 'Milli-Satoshi', unit: 'mSat', value_sat: 0.001,     order: 0 })
  .set('sat',   { code:'sat',   label: 'Satoshi',       unit: 'sat',  value_sat: 1,         order: 1 })
  .set('mbtc',  { code:'mbtc',  label: 'Milli-Bitcoin', unit: 'mBTC', value_sat: 100000,    order: 2, alias: 'bits' })
  .set('btc',   { code:'btc',   label: 'Bitcoin',       unit: 'BTC',  value_sat: 100000000, order: 3 })
)

export function getNextUnitSimple (unit) {
  if (unit == null || unit.code === '') return btcUnits.get('mbtc');
  switch(unit.code) {
    case 'mbtc': return btcUnits.get('btc')
    case 'btc': return btcUnits.get('mbtc')
    default: return btcUnits.get('mbtc')
  }
}
