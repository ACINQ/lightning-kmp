import { getAll } from 'services/ProductService'
import { muts, actions } from 'store/types'

import debug from 'utils/Debug'
const log = debug('starblocks:products-store')

export const products_module = {
  state: {
    products: [],
  },
  mutations: {
    [muts.PRODUCTS_UPDATED] (state, products) {
      state.products = products
    },
  },
  actions: {
    [actions.GET_PRODUCTS] ({ commit, state }) {
      return getAll()
      .then((products) => commit(muts.PRODUCTS_UPDATED, products))
    },
  },
  getters: {
    products: state => state.products,
  }
}
