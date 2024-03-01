<template>
  <div class="shop">
    <cart></cart>
    <div class="products">
      <product v-for="p in products" v-bind:key="p.id" v-bind:product="p"></product>
    </div>
  </div>
</template>

<script>
import { isEmpty } from 'lodash'

import Cart from 'pages/Cart'
import Product from 'pages/Product'

export default {
  components: {
    Cart,
    Product,
  },
  computed: {
    products () {
      return this.$store.getters.products
    },
    cart () {
      return this.$store.getters.cart
    },
    order () {
      return this.$store.getters.order
    },
  }
}
</script>

<style lang="sass">
@import '~styles/settings'

.shop
  position: relative
  .products
    display: flex
    justify-content: center
    flex-wrap: wrap
  .disclaimer
    .disclaimer-title
      font-weight: 700
      margin-bottom: 5px
    background-color: #df9c36
    border-radius: 3px
    margin: 30px auto
    max-width: 420px
    padding: 12px
    color: white
    box-shadow: $shadow
    p
      color: rgba(white, .95)
      &:last-child
        margin: 0
</style>
