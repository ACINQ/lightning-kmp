<template>
  <div class="cart-wrapper">
    <div class="app-logo"><img src="/images/logo_starblocks.png" width="256"/></div>

    <div class="cart">

      <div class="cart-amount">
        <div class="cart-amount--value">{{ total_amount / 100000 | formatAmount }}</div>
        <div class="cart-amount--unit text-xs text-muted">mBTC</div>
      </div>
      <div class="cart-max-reached" v-if="cart.max_reached">
        Max 7 mBTC per order
      </div>
      <div class="cart-action">
        <button class="button checkout" v-on:click="orderCart" :disabled="cart.products.length <= 0">
          <svg-icon id="icon_check" color="white" label="Checkout cart" size="md"></svg-icon>
          Checkout
        </button>
        <button class="button red" v-on:click="emptyCart" :disabled="cart.products.length <= 0">
          <svg-icon id="icon_bin" color="white" label="Empty cart" size="md"></svg-icon>
        </button>
      </div>

      <transition name="slide-up-sm">
        <div class="cart-feedback" v-if="status === 'pending'">
          <div>Please wait...</div>
        </div>
        <div class="cart-feedback text-red" v-if="status === 'failed'">
          <div>Error in checkout, please retry.</div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script>
import debug from 'utils/Debug'
const log = debug('starblocks:cart-view')
import { actions } from '@/store/types'
import { fetching } from '@/utils/Constants'

import SvgIcon from 'components/SvgIcon'

export default {
  components: {
    SvgIcon,
  },
  methods: {
    orderCart () {
      if (this.cart.products.length <= 0 || this.status === fetching.PENDING) return
      this.$store.dispatch(actions.CART_UPDATE_STATUS, fetching.PENDING)
      .then(() => this.$store.dispatch(actions.CREATE_ORDER, this.cart.products))
      .then((o) => console.log('successfully created order %s', JSON.stringify(o)))
      .then(() => {
        this.$store.dispatch(actions.CART_UPDATE_STATUS, fetching.DONE)
        this.$router.push({ name: 'order', params: { id: this.order.id }})
      })
      .catch((e) => {
        console.log('%cerror when creating order', e)
        this.$store.dispatch(actions.CART_UPDATE_STATUS, fetching.FAILED)
        setTimeout(() => this.$store.dispatch(actions.CART_UPDATE_STATUS, fetching.READY), 1700)
      })
    },
    emptyCart () {
      this.$store.dispatch(actions.EMPTY_CART)
      .then(() => console.log('successfully clear cart'))
      .catch((e) => console.log('%cerror when emptying cart', 'color:red', e))
    },
  },
  computed: {
    status () {
      return this.$store.getters.cart.status
    },
    cart () {
      return this.$store.getters.cart
    },
    order () {
      return this.$store.getters.order
    },
    total_amount () {
      return this.cart.products.reduce((a,b) => a + b.unitary_price_satoshi * b.count, 0)
    },
  }
}
</script>

<style lang="sass">
@import '~styles/settings'

.cart-wrapper
  position: relative
  margin: 0px auto 30px
  display: flex
  flex-direction: row
  align-items: center
  justify-content: center
  width: 50%
  min-width: 300px
  @include md
    width: 185px
    min-width: 0
    align-items: stretch
    flex-direction: column

.app-logo
  position: relative
  z-index: 15
  max-width: 150px
  flex: 0 0 auto
  @include md
    max-width: none
  img
    width: 100%

.cart
  flex: 0 0 auto
  position: relative
  background-color: $ocher
  border: 5px solid $ivory
  box-shadow: $shadow
  color: $default
  overflow: hidden
  padding: 0 0 0 50px
  margin: 0 0 0 -50px
  border-radius: 0 10px 10px 0
  @include md
    border-radius: 0 0 10px 10px
    margin: -120px 20px 0
    padding: 120px 0 0

  .cart-amount
    padding: 8px 10px
    @include md
      padding: 3px 5px
    display: flex
    align-items: baseline
    .cart-amount--value
      text-align: right
      font-size: 22px
      margin-right: 3px
      flex: 1 1 auto
    .cart-amount--unit
      flex: 0 0 auto

  .cart-max-reached
    padding: 0 10px 5px
    color: rgba($red,.7)
    text-align: center
    font-size: 10px

  .cart-action
    position: relative
    display: flex
    flex-wrap: nowrap
    @include sm_only
      margin-left: -20px

    button.button
      border-radius: 0
      flex: 1 1 auto
      &:not(:last-child)
        border-right: 1px solid #359677
      &.checkout
        @include sm_only
          padding-left: 20px
        svg
          margin-right: 5px


  .cart-feedback
    position: absolute
    z-index: 10
    background-color: $ocher
    top: 0
    bottom: 0
    left: 0
    right: 0
    font-size: $font-xs
    display: flex
    align-items: stretch
    flex-direction: column
    justify-content: center
    padding-left: 40px
    width: 100%
    @include md
      justify-content: flex-end
      padding-left: 0
    & > div
      padding: 10px
      text-align: center

</style>
