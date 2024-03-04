import { createOrder, getOrder } from 'services/OrderService'
import { muts, actions } from 'store/types'
import { isEmpty } from 'lodash'
import { init_websocket } from 'services/WSocket'

import debug from 'utils/Debug'
const log = debug('starblocks:order-store')

export const order_module = {
  state: {
    order: {},
  },
  mutations: {
    [muts.ORDER_UPDATE] (state, order) {
      log('order is now %s', JSON.stringify(order))
      state.order = order
    },
    [muts.ORDER_PAID] (state) {
      if (!isEmpty(state.order)) {
        log('order was paid with paymentHash=%s', state.order.payment_hash)
        state.order.paid = true
      } else {
        log('tried to pay an empty order...')
      }
    },
  },
  actions: {
    [actions.GET_ORDER] ({ commit, state }, order_id) {
      return getOrder(order_id)
      .then((order) => {
        commit(muts.ORDER_UPDATE, order)
        return order
      })
    },
    [actions.OPEN_ORDER_WS] ({ commit, state }, paymentHash) {
      return init_websocket(paymentHash)
      .then((socket) => {
        socket.onmessage = (event) => {
          let data = JSON.parse(event.data)
          log('received %s from websocket', data)
          if (data.paymentHash === state.order.payment_hash) {
            commit(muts.ORDER_PAID)
            //socket.close()
          }
        }
        socket.onclose = (event) => {
          log('websocket lost connection with server')
        }
        socket.onerror = (event) => {
          log('websocket encountered error')
        }
      })
    },
    [actions.CREATE_ORDER] ({ commit, state }, products) {
      return createOrder(products)
      .then((order) => {
        commit(muts.CART_CLEAR)
        return commit(muts.ORDER_UPDATE, order)
      })
    },
    [actions.CANCEL_ORDER] ({ commit, state }) {
      return commit(muts.ORDER_UPDATE, {})
    },
  },
  getters: {
    order: state => state.order,
  }
}
