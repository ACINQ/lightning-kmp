#include <Network/Network.h>

typedef void (^ssf_connected_handler_t)(nw_connection_t _Nullable connection, nw_error_t _Nullable error);
void ssf_connect(const char * _Nonnull hostname, const char * _Nonnull port, bool tls, ssf_connected_handler_t _Nonnull handler);

typedef void (^ssf_send_handler_t)(nw_error_t _Nullable error);
void ssf_send(nw_connection_t _Nonnull connection, void * _Nullable buffer, size_t size, bool flush, ssf_send_handler_t _Nonnull handler);

typedef void (^ssf_receive_handler_t)(bool isComplete, size_t size, nw_error_t _Nullable error);
void ssf_receive(nw_connection_t _Nonnull connection, void * _Nonnull buffer, uint32_t min, uint32_t max, ssf_receive_handler_t _Nonnull handler);

void ssf_cancel(nw_connection_t _Nonnull connection);
