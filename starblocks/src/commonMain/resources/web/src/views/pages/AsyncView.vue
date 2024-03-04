<script>

import { fetching } from 'constants'
import LoadingBar from 'components/LoadingBar'

import debug from 'utils/Debug'
const log = debug('starblocks:async')

export default {
  data () {
    return {
      status: fetching.READY
    }
  },
  components: {
    LoadingBar,
  },
  methods: {
    getContent (h) {
      return (<div class="aync__content">Internal</div>)
    },
    getContentPending (h) {
      return (<div class="async__pending">Retrieving Content...</div>)
    }
  },
  render (h) {
    let content = (<div>
      Initializing
    </div>)
    log('%cstatus', 'color: #00d1b2', this.status)
    switch (this.status) {
      case fetching.PENDING:
        content = this.getContentPending(h)
        break
      case fetching.FAILED:
        content = (<div class="async__error text-danger text-center text-sm">Content could not be retrieved.</div>)
        break
      case fetching.DONE:
        content = this.getContent(h)
        break
      default:
        break
    }
    return (
      <div class="async">
        <loading-bar progress={this.status}></loading-bar>
        {content}
      </div>
    )
  }
}
</script>

<style lang="sass">
@import '~styles/settings'
.async__error
  padding: 10px
</style>
