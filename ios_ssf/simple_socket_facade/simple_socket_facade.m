
#include "simple_socket_facade.h"

void ssf_connect(const char * _Nonnull hostname, const char * _Nonnull port, bool tls, ssf_connected_handler_t _Nonnull handler) {

    nw_endpoint_t endpoint = nw_endpoint_create_host(hostname, port);

    nw_parameters_t parameters = nw_parameters_create_secure_tcp(
            tls ? NW_PARAMETERS_DEFAULT_CONFIGURATION : NW_PARAMETERS_DISABLE_PROTOCOL,
            NW_PARAMETERS_DEFAULT_CONFIGURATION
    );

    nw_connection_t connection = nw_connection_create(endpoint, parameters);
//    nw_retain(connection);

//    nw_release(endpoint);
//    nw_release(parameters);

    nw_connection_set_queue(connection, dispatch_get_main_queue());

    __block bool called = false;
    nw_connection_set_state_changed_handler(connection, ^(nw_connection_state_t state, nw_error_t _Nullable error) {
        switch (state) {
            case nw_connection_state_ready:
                if (!called) {
                    called = true;
                    handler(connection, nil);
                }
                break ;
            case nw_connection_state_failed:
                if (!called) {
                    called = true;
                    handler(nil, error);
                }
                break ;
            case nw_connection_state_cancelled:
//                nw_release(connection);
                break ;
            default: break ;
        }
    });

    nw_connection_start(connection);
}

void ssf_send(nw_connection_t _Nonnull connection, void * _Nullable buffer, size_t size, bool flush, ssf_send_handler_t _Nonnull handler) {
    dispatch_data_t data = nil;
    if (buffer != nil) {
        // dispatch_data_create copies the buffer if DISPATCH_DATA_DESTRUCTOR_DEFAULT (=null),
        // is specified, and attempts to free the buffer if DISPATCH_DATA_DESTRUCTOR_FREE is specified,
        // so an empty destructor is specified.
        data = dispatch_data_create(buffer, size, dispatch_get_main_queue(), ^{});
    }

    nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, flush, ^(nw_error_t _Nullable error) {
//        dispatch_release(data);
        handler(error);
    });
}

void ssf_receive(nw_connection_t _Nonnull connection, void * _Nonnull buffer, uint32_t min, uint32_t max, ssf_receive_handler_t _Nonnull handler) {
    nw_connection_receive(connection, min, max, ^(dispatch_data_t _Nullable content, nw_content_context_t _Nullable context, bool is_complete, nw_error_t _Nullable error) {
        if (content != nil) {
            dispatch_data_apply(content, ^bool(dispatch_data_t region_data, size_t offset, const void *region_buffer, size_t size) {
                memcpy(buffer + offset, region_buffer, size);
                return 1;
            });
        }
        handler(is_complete, dispatch_data_get_size(content), error);
    });
}

void ssf_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_cancel(connection);
}