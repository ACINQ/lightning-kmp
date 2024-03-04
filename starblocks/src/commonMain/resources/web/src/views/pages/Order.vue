<template>
  <div class="order">
    <div class="order-body" v-if="status === 'done'">
      <div class="order-details">
        <div class="order-title text-center">Order <strong>#{{ order.id }}</strong></div>
        <div class="order-amount" v-on:click="switchUnit">
          {{ amountInUnit(order.amount_satoshi) }} <span class="unit">{{ unit.unit }}</span>
        </div>
        <ul class="order-products">
          <li v-for="(item, index) in order.items"
              v-bind:key="index">
            <div class="order-product--name text-ellipsis">{{ getProductName(item.product_id) }}</div>
            <div class="order-product--count">&times; {{ item.count }}</div>
          </li>
        </ul>
      </div>
      <transition name="paid" mode="out-in" :duration="{ enter: 2000, leave: 0 }">>
        <div class="order-payment" v-if="!order.paid" key="notpaid">
          <div class="order-title order-pr--title">Scan this invoice with your <strong>LN-enabled</strong> wallet</div>
          <div class="order-container order-pr">
            <div class="order-qr-code">
              <qrcode :value="payment_request_with_scheme" :options="qr_opts" v-if="order.payment_request" />
            </div>
            <div class="order-pr--value"><span>{{ order.payment_request }}</span></div>
            <a :href="payment_request_with_scheme" class="button ocher">Open with your wallet</a>
            <button type="button" class="button copy ocher" title="Copy to clipboard" v-clipboard:copy="order.payment_request">
              <svg-icon id="icon_copy" label="Copy" size="lg"></svg-icon>
              <span>Copy</span>
            </button>
          </div>
        </div>
        <div id="paidbox" class="order-payment order-paid" v-else key="paid">
          <div class="order-container">
            <img class="order-check" src="/images/icon_check_circle_green.png" width="200" />
          </div>
        </div>
      </transition>
    </div>
    <div class="order-failed" v-if="status === 'failed'">
      This order could not be found
    </div>
    <div class="order-fetching" v-if="status === 'pending'">
      Retrieving order
    </div>
    <div class="order-home">
      <router-link to="/" class="button text-sm" tag="button">Go to Home</router-link>
    </div>
  </div>
</template>

<script>
import debug from 'utils/Debug'
const log = debug('starblocks:order-view')
import { actions } from 'store/types'
import { find } from 'lodash'
import { isBlank } from '@/utils/StringUtils'
import { API_URL } from '@/utils/ServicesUtils'
import { fetching } from '@/utils/Constants'
import { convertSatToUnit, btcUnits, getNextUnitSimple } from '@/utils/Bitcoin'

import SvgIcon from 'components/SvgIcon'
import Cart from 'pages/Cart'

export default {
  components: {
    SvgIcon,
    Cart,
  },
  data () {
    return {
      status: fetching.READY,
      unit: btcUnits.get('sat'),
      paymentHeight: 0,
      qr_opts: {
        width: 260,
        errorCorrectionLevel: 'L',
        margin: 6
      }
    }
  },

  created () {
    log('loading order %s', this.route_order_id)
    if (!isBlank(this.route_order_id)) {
      this.status = fetching.PENDING
      this.$store.dispatch(actions.GET_ORDER, this.route_order_id)
      .then((o) => {
        log('Successfully fetched order %s', JSON.stringify(o))
        this.status = fetching.DONE
        return o
      })
      .then((o) => this.$store.dispatch(actions.OPEN_ORDER_WS, o.payment_hash))
      .catch((e) => {
        log('error when fetching order', e)
        this.status = fetching.FAILED
      })
    }
  },
  methods: {
    getProductName (product_id) {
      const p = find(this.products, {id: product_id})
      return p ? p.name : 'unknown product name'
    },
    cancelOrder () {
      log('cancel order -> go back to cart')
      this.$store.dispatch(actions.CANCEL_ORDER) //todo api delete
      .then(() => {
        this.$store.dispatch(actions.EMPTY_CART)
      })
      .then(() => {
        log('successfully cancelled order')
        this.$router.push({ name: 'shop' })
      })
      .catch((e) => log('%cerror when cancelling order', e))
    },
    switchUnit () {
      this.unit = getNextUnitSimple(this.unit)
    },
    amountInUnit (amountSat) {
      const am = convertSatToUnit(amountSat, this.unit.code)
      return this.$options.filters.formatAmount(am)
    },
  },
  computed: {
    API_URL () {
      return API_URL
    },
    path () {
      return this.$store.state.route.path
    },
    route_order_id () {
      const id = this.$store.state.route.params.id
      return isBlank(id) ? '' : id
    },
    products () {
      return this.$store.getters.products
    },
    cart () {
      return this.$store.getters.cart
    },
    order () {
      return this.$store.getters.order
    },
    payment_request_with_scheme () {
      return this.order.payment_request.startsWith('lightning:') ? this.order.payment_request : 'lightning:' + this.order.payment_request
    }
  }
}
</script>

<style lang="sass">
@import '~styles/settings'

.order
  padding: 10px
  @include md
    padding: 20px
  position: relative

.order-failed, .order-fetching
  position: relative
  max-width: 400px
  margin: 0 auto
  border-radius: 3px
  box-shadow: $shadow
  padding: 20px
  background-color: $ocher
  text-align: center
.order-failed
  color: $red

.order-body
  position: relative
  max-width: 400px
  margin: 0 auto
  border-radius: 3px
  overflow: hidden
  box-shadow: $shadow

  .order-details
    background-color: #f7e2b1
    padding: 15px

  .order-title
    font-size: $font-xs
    color: rgba($default, .6)
    text-transform: uppercase

  .order-amount
    font-size: 24px
    margin: 10px 0 5px
    @include md
      font-size: 40px
      margin: 30px 0 10px
      .unit
        font-size: 10px
    text-align: center
    user-select: none
    cursor: alias
    .unit
      font-size: 12px

  ul.order-products
    padding: 0
    margin: 0
    font-size: $font-sm
    li
      list-style-type: none
      display: flex
      padding: 3px 0
      &:not(:last-child)
        border-bottom: 1px dotted rgba($default,.3)
      .order-product--name
        flex: 1 1 auto
      .order-product--count
        padding-left: 5px
        flex: 0 0 auto

.order-payment
  background-color: #f3d899

  .order-container
    padding: 15px

  .order-pr--title
    padding: 15px
    background-color: #e3ca92
    text-align: center

  .order-qr-code
    width: 260px
    margin: 25px auto
    canvas
      display: block
      width: 100%
  img.order-check
    display: block
    margin: 15px auto
    width: 100px
  .order-pr--value
    word-break: break-all
    font-family: $family-mono
    margin-bottom: 10px
  .order-pr > a,
  .order-pr > button
    display: inline-block
    font-size: 10px
    text-transform: uppercase
    line-height: 1
    padding: 7px
    margin-right: 5px
    svg
      fill: $default

.order-help, .order-home
  margin: 0 auto
  width: 100px
  button.button
    color: white
    width: 100%
    border-radius: 0 0 5px 5px

.paid-enter-active
  transition: all .5s cubic-bezier(.5,0,0,1), background-color 2s ease
.paid-enter
  &.order-paid
    transform: translate3d(0,100px,0) scaleY(0)
    background-color: $green
    height: 0
    opacity: 0
.paid-enter-to
  &.order-paid
    height: 160px

</style>
