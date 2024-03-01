import Vue from 'vue'
import Vuex from 'vuex'

// modules
import { cart_module } from './store_cart'
import { products_module } from './store_products'
import { order_module } from './store_order'

Vue.use(Vuex)

const store = new Vuex.Store({
  strict: process.env.NODE_ENV !== 'production',
  modules: {
    cart_module,
    products_module,
    order_module,
  },
})

export default store
