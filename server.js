'use strict';

const net = require('net');

// TCP Server
let tcpServer = net.createServer((socket) => {
    console.log('New socket connected');

    socket.on('data', data => {
        console.log('New data on socket', data.toString('ascii'));

        if (data.toString('ascii') === 'ping') {
            socket.write('pong');
        }else{
						socket.write(data);
				}
    });

    socket.on('close', () => {
        console.log('Socket closed');
    })
});

tcpServer.listen(1885, '0.0.0.0');

console.log('Listening on port 1885');