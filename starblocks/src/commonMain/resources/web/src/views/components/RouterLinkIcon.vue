<template>
  <router-link :to="to" class="button-icon text-upper text-thin text-sm" :class="color + ' ' + size">
    <div v-if="icon" class="icon">
      <svg-icon :id="icon" label="Login" size="lg"></svg-icon>
    </div>
    <div class="button-icon-label">
      <slot></slot>
    </div>
  </router-link>
</template>

<script>
import SvgIcon from 'components/SvgIcon'

export default {
  name: 'currency',
  props: {
    icon: {
      type: String
    },
    to: {
      type: String,
      required: true
    },
    color: {
      type: String,
      default: 'grey'
    },
    size: {
      type: String,
      default: ''
    }
  },
  components: {
    SvgIcon
  }
};
</script>

<style lang="sass">
@import "~styles/settings"
a.button-icon
  display: inline-block
  border-radius: 2px
  text-decoration: none
</style>
