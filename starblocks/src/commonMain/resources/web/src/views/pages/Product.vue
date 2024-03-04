<template>
  <div class="product-wrapper">
    <div class="product">
      <div class="product-illustration">
        <img class="product-image" :src="'/images/' + product.label + '.jpg'" />
        <div class="product-price">{{ product.price_satoshi }} sat</div>
      </div>
      <div class="product-content">
        <div class="product-name text-cursive">{{ product.name }}</div>
        <div class="product-desc">{{ product.description }}</div>
      </div>
      <div class="product-action">
        <button class="button" v-on:click="addProductToStore" :disabled="disabled">
          <svg-icon id="icon_cart" color="white" label="Add to cart" size="md"></svg-icon>
          Add to cart!
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { actions } from 'store/types'
import { fetching } from 'utils/Constants'
import debug from 'utils/Debug'
const log = debug('starblocks:product-view')

import SvgIcon from 'components/SvgIcon'

export default {
  props: {
    product: {
      type: Object,
      required: true,
    }
  },
  computed: {
    disabled () {
      return this.$store.getters.cart.status === fetching.PENDING
    },
  },
  methods: {
    addProductToStore () {
      this.$store.dispatch(actions.ADD_TO_CART, this.product)
      .then(() => log('added %s to cart', this.product.name))
      .catch((e) => log('error when adding product %s [%s]', this.product.id, e))
    }
  },
  components: {
    SvgIcon,
  },
}
</script>

<style lang="sass">
@import '~styles/settings'

.product-wrapper
  padding: 10px 30px
  &:hover .product .product-content .product-name
    background-color: $ocher-pop
  &:hover .product .product-illustration .product-image
    transform: none
.product
  padding: 0
  background-color: transparent
  width: 120px
  @include md
    width: 150px
  @include lg
    width: 180px

  a
    display: block

  .product-illustration
    margin: 0 10px
    position: relative
    z-index: 1
    img.product-image
      display: block
      border-radius: 10px 10px 0 0
      overflow: hidden
      transition: all .4s cubic-bezier(.5,0,0,1)
      box-shadow: 0 0px 30px 0 rgba(0,0,0,.3)
      transform: translate3d(0,20px,0)
      width: 100%
    .product-price
      position: absolute
      left: 0
      right: 0
      bottom: 0
      padding: 10px
      color: $ivory
      line-height: 1
      background: linear-gradient(0deg, rgba($default, .8), transparent 100%)
      text-shadow: 2px 2px 5px $default
      text-align: right

  .product-content
    position: relative
    z-index: 6
    .product-name
      position: relative
      z-index: 2
      margin: 0 -15px
      text-align: center
      background-color: $ocher
      padding: 5px 7px 7px
      font-size: $font-sm
      cursor: default
      @include md
        padding: 8px 10px 10px
        font-size: $font-md
      @include lg
        font-size: $font-lg
      border: 4px solid $ivory
      transition: all .2s ease

    .product-desc
      margin-top: -4px
      background-color: $ivory
      color: #b79d61
      border-radius: 0 0 10px 10px
      overflow: hidden
      box-shadow: $shadow
      padding: 6px 10px
      font-size: $font-xs
      @include md
        font-size: $font-sm
        padding: 10px 15px

  .product-action
    padding: 0 10px
    z-index: 1
    button.button
      display: block
      width: 100%
      margin: -10px 0 0
      box-shadow: $shadow
      border-radius: 0 0 5px 5px
      padding: 16px 6px 6px
      font-size: $font-xs
      @include md
        border-radius: 0 0 10px 10px
        padding: 20px 10px 10px
      svg.svg-icon
        width: 10px
        height: 10px

</style>
