<template>
  <div id="app" class="app">
    <div class="app-content">
      <transition name="slide-left" mode="out-in">
        <router-view class="view"></router-view>
      </transition>
    </div>
    <div class="poweredby"><a href="https://github.com/ACINQ/phoenix" target="_blank"><img src="/images/poweredby.png" height="100" /></a></div>
  </div>
</template>

<script>
import { actions } from 'store/types'
import debug from 'utils/Debug'
const log = debug('starblocks:app')

export default {
  computed: {
    products () {
      return this.$store.getters.products
    },
    cart () {
      return this.$store.getters.cart
    }
  },
  created () {
    // init application store (products & cart)
    this.$store.dispatch(actions.GET_PRODUCTS)
    .then(() => log('%s products fetched from server', this.products.length))
    .catch((e) => log('error when initializing the cart', e))
  }
}
</script>

<style lang="sass">
@import '~styles/app'
.app
  position: relative
  min-height: 100%
  width: 100%
  padding: 20px 0 100px
  @media screen and (min-height: 550px)
    padding: 80px 0 100px

  .poweredby
    position: absolute
    bottom: 30px
    width: 100%
    text-align: center
    a
      display: inline-block
      padding: 10px
      border-radius: 5px
      opacity: .9
      transition: background-color .2s ease, opacity .5s ease
      &:hover, &:focus
        opacity: 1
        background-color: rgba(white, 0.1)
      img
        height: 33px
        display: block

.app-content
  max-width: 2000px
  margin: 0 auto
</style>
