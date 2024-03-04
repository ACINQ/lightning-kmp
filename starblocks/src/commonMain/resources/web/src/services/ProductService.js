import 'whatwg-fetch'
import { API_URL, prepareJsonResponse, giveBasic, encodeQueryParams } from 'utils/ServicesUtils'
import { isBlank } from 'utils/StringUtils'

import debug from 'utils/Debug'
const log = debug('starblocks:product-service')

/**
 * Get all products
 * @return a Promise containing the product list
 */
export function getAll () {
  return Promise.resolve().then(() => {
    return fetch(API_URL + '/products', {
      method: 'GET',
      mode: 'cors',
      cache: 'no-cache',
    })
  })
  .then(prepareJsonResponse)
}
