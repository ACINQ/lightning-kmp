import { muts, actions } from 'store/types'
import { isBlank } from 'utils/StringUtils'
import { findIndex } from 'lodash'
import { fetching } from 'utils/Constants'
import debug from 'utils/Debug'
const log = debug('starblocks:cart-store')

export const cart_module = {
  state: {
    cart: {
      products: [],
      lastupdate: Date.now(),
      status: fetching.READY,
      max_reached: false,
    },
  },
  mutations: {
    [muts.CART_STATUS] (state, status) {
      state.cart.status = status
    },
    [muts.CART_ADD] (state, product) {
      const idx = findIndex(state.cart.products, { id: product.id })
      if (idx >= 0) {
        state.cart.products[idx].count++
        state.cart.products[idx].unitary_price_satoshi = product.price_satoshi
      } else {
        state.cart.products.push({
          id: product.id,
          name: product.name,
          unitary_price_satoshi: product.price_satoshi,
          count: 1
        })
      }
      state.cart.lastupdate = Date.now()
    },
    [muts.CART_MAX_REACHED] (state) {
      state.cart.max_reached = true
    },
    [muts.CART_CLEAR] (state) {
      state.cart.products = []
      state.cart.max_reached = false
      state.cart.lastupdate = Date.now()
    }
  },
  actions: {
    [actions.EMPTY_CART] ({ commit, state }) {
      return commit(muts.CART_CLEAR)
    },
    [actions.CART_UPDATE_STATUS] ({ commit, state }, status) {
      return commit(muts.CART_STATUS, status)
    },
    [actions.ADD_TO_CART] ({ commit, state }, product) {
      if (!product || isBlank(product.id) || isNaN(product.price_satoshi)) {
        return Promise.reject('invalid product')
      }
      const total = state.cart.products.reduce((a,b) => a + b.unitary_price_satoshi * b.count, 0) + product.price_satoshi
      log('total cart is = ' + total)
      return commit(muts.CART_ADD, product)
    },
  },
  getters: {
    cart: state => state.cart,
  }
}
