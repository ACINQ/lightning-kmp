import Vue from 'vue'
import Router from 'vue-router'

import NotFound from 'pages/404'
import Shop from 'pages/Shop'
import Order from 'pages/Order'

import store from '../store/store'
import debug from 'utils/Debug'
const log = debug('starblocks:router')

Vue.use(Router)

const routes = [
  { path: '/', name: 'shop', component: Shop },
  { path: '/order/:id', name: 'order', component: Order },
  { path: '*', name: '404', component: NotFound },
]

const router = new Router({
  mode: 'history',
  routes,
  linkActiveClass: 'active'
})

export default router;
