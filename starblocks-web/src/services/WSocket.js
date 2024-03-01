import { isBlank } from 'utils/StringUtils'
import { isNil } from 'lodash'
import { API_HOST } from 'utils/ServicesUtils'

import debug from 'utils/Debug'
const log = debug('starblocks:wsocket')

const wsUri = process.env.NODE_ENV === 'production'
  ? 'wss://' + API_HOST + '/ws-order'
  : 'ws://' + API_HOST + '/ws-order'

// only one socket should be opened
let socket = null

/**
 * Open a connection to {wsUri}
 */
export function init_websocket (paymentHash) {

  if (!isNil(socket)) {
    log('closing socket with status ' + socket.readyState)
    socket.close()
    socket = null
  }

  return new Promise((resolve, reject) => {
    if (isBlank(paymentHash)) reject('invalid payment hash, could not open WS')
    socket = new WebSocket(wsUri + '/' + paymentHash)
    resolve(socket)
  })
}
