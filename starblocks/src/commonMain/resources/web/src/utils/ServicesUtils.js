import { isBlank, isNull } from './StringUtils'

export const API_HOST = process.env.NODE_ENV === 'production' ? window.location.host : 'localhost:8081'
export const API_URL = process.env.NODE_ENV === 'production' ? 'https://' + API_HOST + '/api' : 'http://' + API_HOST + '/api'

export function prepareJsonResponse (response) {
  if (response.status >= 200 && response.status < 300) {
    return Promise.resolve(response.json())
  } else {
    log("error response: $s", response)
    const error = new Error(response.status)
    error.response = response
    return Promise.reject(error)
  }
}

function buildBasic (login, password) {
  return 'Basic ' + btoa(login.trim() + ':' + password.trim())
}

/**
 * Builds a BASIC auth string (basic + btoa(login:pw)) as a promise.
 * Accepts empty parameters (but not null/undefined)
 */
export function giveBasic (login, password) {
  return new Promise((resolve, reject) => {
      if (isNull(login) | isNull(password)) {
        reject('Invalid Auth Data')
      }
      resolve(buildBasic (login, password))
    }
  )
}

/**
 * Builds a BASIC auth string (basic + btoa(login:pw)) as a promise.
 * Rejected if login/password is null, undefined, empty
 */
export function giveBasicStrict (login, password) {
  return new Promise((resolve, reject) => {
      if (isBlank(login) | isBlank(password)) {
        reject('Invalid Auth Data')
      }
      resolve(buildBasic (login, password))
    }
  )
}

export function encodeQueryParams (map) {
   let filter = [];
   for (let k in map)
     filter.push(encodeURIComponent(k) + '=' + encodeURIComponent(map[k]));
   return filter.join('&');
}
