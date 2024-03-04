<template>
  <svg width="10" height="10" :class="'svg-icon ' + size + ' ' + color" :aria-label="label">
    <title>{{ label }}</title>
    <use :xlink:href="'#' + id"></use>
  </svg>
</template>

<script>
import icon_check from 'assets/icons/icon_check.svg'
import icon_bin from 'assets/icons/icon_bin.svg'
import icon_cart from 'assets/icons/icon_cart.svg'
import icon_copy from 'assets/icons/icon_copy.svg'

export default {
  props: {
    id: {
      type: String,
      required: true
    },
    label: {
      type: String,
      required: true
    },
    size: {
      type: String,
      required: false,
      default: ''
    },
    color: {
      type: String,
      required: false
    }
  },
}
</script>

<style lang="sass">
@import '~styles/settings'

svg.svg-icon
  fill: rgba($primary, .9)
  display: inline
  &:not(:root)
    overflow: visible
  &.sm
    width: .5em
    height: .5em
  &.md
    width: .75em
    height: .75em
  &.lg
    width: 1em
    height: 1em
  &.xl
    width: 1.5em
    height: 1.5em
  &.white
    fill: white

</style>
