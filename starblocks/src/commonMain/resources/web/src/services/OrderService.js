import 'whatwg-fetch'
import { API_URL, prepareJsonResponse, giveBasic, encodeQueryParams } from 'utils/ServicesUtils'
import { isBlank } from 'utils/StringUtils'
import debug from 'utils/Debug'
const log = debug('starblocks:order-service')

/**
 * Creates a new order

 * @return a Promise containing the new order
 */
export function createOrder (products) {

  if (products.length <= 0) return Promise.reject('No products found in order')

  const reqData = JSON.stringify(products.map(p => {
    return {
      product_id: p.id,
      count: p.count,
      unitary_price_satoshi: p.unitary_price_satoshi,
      product_name: p.name,
    }
  }))
  log("api call to /order with products=%s", reqData)

  return Promise.resolve().then(() => {
    return fetch(API_URL + '/order', {
      method: 'POST',
      mode: 'cors',
      cache: 'no-cache',
      body: reqData,
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      }
    })
  })
  .then(prepareJsonResponse)
}

/**
 * Fetches an order
 *
 * @param order_id the id of the order, must not be blank
 * @return a Promise containing the order
 */
export function getOrder (order_id) {
  if (isBlank(order_id)) return Promise.reject('Invalid order id')
  return Promise.resolve().then(() => {
    return fetch(API_URL + '/order/' + order_id.trim(), {
      method: 'GET',
      mode: 'cors',
      cache: 'no-cache'
    })
  })
  .then(prepareJsonResponse)
}
