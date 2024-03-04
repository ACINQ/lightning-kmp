import debug from 'debug'

if (process.env.NODE_ENV !== 'production') {
  debug.enable('starblocks:*')
} else {
  debug.disable()
}

export default debug
