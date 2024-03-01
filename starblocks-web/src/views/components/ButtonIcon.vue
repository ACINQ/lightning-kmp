<template>
  <button :type="type" class="button-icon button text-upper text-thin" :class="color + ' ' + size" :disabled="disabled">
    <div v-if="icon" class="icon">
      <svg-icon :id="icon" label="Login" size="lg"></svg-icon>
    </div>
    <div class="button-icon-label">
      <slot></slot>
    </div>
  </button>
</template>

<script>
import SvgIcon from 'components/SvgIcon'

export default {
  name: 'currency',
  props: {
    icon: {
      type: String
    },
    disabled: {
      type: Boolean,
      default: false
    },
    type: {
      type: String,
      default: 'submit'
    },
    color: {
      type: String,
      default: 'grey'
    },
    size: {
      type: String,
      default: ''
    },
  },
  components: {
    SvgIcon
  }
};
</script>

<style lang="sass">
@import "~styles/settings"
.button.button-icon
  padding: 0

.button-icon
  position: relative
  border: none
  outline: 0
  letter-spacing: 0.07em
  background-color: transparent
  transition: background-color 0.25s ease-out, color 0.25s ease-out
  text-align: left
  font-size: $font-sm

  .button-icon-label
    color: white
    margin-left: 30px
    padding: 8px 10px
    flex-grow: 1

  &.transparent
    .icon
      background: transparent
  &.grey
    background-color: $grey-light
    &:hover, &:focus, &:active
      background-color: darken($grey-light, 4%)
  &.red
    background-color: $red
    &:hover, &:focus, &:active
      background-color: darken($red, 4%)
  &.green
    background-color: $green
    &:hover, &:focus, &:active
      background-color: darken($green, 4%)
  &.yellow
    background-color: $yellow
    .button-icon-label
      color: $dark
    .icon
      background: rgba(white, .25)
      svg
        fill: $dark
    &:hover, &:focus, &:active
      background-color: darken($yellow, 15%)
      .button-icon-label
        color: $dark
  &.turquoise
    background-color: $turquoise
    &:hover, &:focus, &:active
      background-color: darken($turquoise, 4%)

  &.sm
    .button-icon-label
      padding: 4px 6px
      font-size: $font-xs
      font-weight: 400

  .icon
    background: rgba(white, .15)
    position: absolute
    top: 0
    bottom: 0
    left: 0
    width: 30px
    display: flex
    align-items: center
    justify-content: center
    svg
      fill: white
</style>
