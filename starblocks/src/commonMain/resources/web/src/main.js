import Vue from 'vue'
import App from 'views/App'
import VueQrcode from '@chenfengyuan/vue-qrcode'
import VueClipboard from 'vue-clipboard2'

import { formatAmount } from './utils/Filters'

import { sync } from 'vuex-router-sync'
import store from './store/store.js'
import router from './router/router.js'

sync(store, router)

Vue.use(VueClipboard)
Vue.component('qrcode', VueQrcode);

Vue.filter('formatAmount', formatAmount)

Vue.config.productionTip = false

/* eslint-disable no-new */
new Vue({
  router,
  store,
  render: h => h(App)
}).$mount('#app')
