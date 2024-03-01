const path = require('path')

function resolve(dir) {
  return path.join(__dirname, dir)
}

const { defineConfig } = require('@vue/cli-service')
module.exports = defineConfig({
  transpileDependencies: true,
  devServer: {
    host: '0.0.0.0',
    port: 9875,
    https: false,
  },
  chainWebpack: (config) => {
    const svgRule = config.module.rule('svg');
    svgRule.uses.clear();
    svgRule.delete('type');
    svgRule.delete('generator');
    // svgRule
    //   .use('vue-svg-loader')
    //   .loader('vue-svg-loader');
    svgRule
      .test(/\.svg$/)
      .use('file-loader')
      .loader('svg-sprite-loader')
      .end()

    config.module
      .rule('raw-loader')
      .test(/\.txt$/)
      .use('raw-loader')
      .loader('raw-loader')
      .end()

    config.resolve.alias
      .set('assets', resolve('src/assets'))
      .set('icons', resolve('src/assets/icons'))
      .set('styles', resolve('src/styles'))
      .set('views', resolve('src/views'))
      .set('components', resolve('src/views/components'))
      .set('pages', resolve('src/views/pages'))
      .set('store', resolve('src/store'))
      .set('utils', resolve('src/utils'))
      .set('services', resolve('src/services'))
  },
})