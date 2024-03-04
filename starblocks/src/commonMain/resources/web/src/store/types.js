// -------- mutations
export const muts = {
  CART_ADD: 'muts_cart_add',
  CART_CLEAR: 'muts_cart_clear',
  PRODUCTS_UPDATED: 'muts_products_updated',
  ORDER_UPDATE: 'muts_order_update',
  ORDER_PAID: 'muts_order_paid',
  CART_STATUS: 'muts_cart_status',
  CART_MAX_REACHED: 'cart_max_reached',
}

export const actions = {
  // cart
  EMPTY_CART: 'action_empty_cart',
  ADD_TO_CART: 'action_add_cart',
  CART_UPDATE_STATUS: 'muts_update_cart_status',
  // products
  GET_PRODUCTS: 'action_get_products',
  CREATE_ORDER: 'action_create_order',
  OPEN_ORDER_WS: 'action_open_order_websocket',
  CANCEL_ORDER: 'action_cancel_order',
  GET_ORDER: 'action_get_order',
}
