# omz-proxy 3 HTTP/2 plugin

This plugin adds support for HTTP/2 to [omz-proxy3](https://git.omegazero.org/omz-infrastructure/omz-proxy3).

HTTP/2 over plaintext (h2c) for clients will probably never be supported.\
Prioritization is currently unsupported, but support is likely going to be added in the future.


## Configuration

### Plugin Configuration Object

Configuration ID: `http2`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enable | boolean | Whether to enable HTTP/2 support by registering the "h2" TLS ALPN option. | no | true |

### HTTP Engine Configuration Object

Configuration ID: `HTTP2`

All [common HTTP engine parameters are supported](https://git.omegazero.org/omz-infrastructure/omz-proxy3#common-http-engine-parameters), in addition to the ones listed below.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| maxFrameSize | number | The maximum HTTP/2 frame payload size in bytes (HTTP/2 setting: MAX_FRAME_SIZE). | no | 16384 `(http2 default)` |
| maxDynamicTableSize | number | The maximum size in bytes of the HPACK dynamic table used by the decoder (HTTP/2 setting: HEADER_TABLE_SIZE). | no | 4096 `(http2 default)` |
| initialWindowSize | number | The initial flow control window size in bytes (HTTP/2 setting: INITIAL_WINDOW_SIZE). | no | 65535 `(http2 default)` |
| maxConcurrentStreams | number | The maximum number of concurrent streams (HTTP/2 setting: MAX_CONCURRENT_STREAMS). Should be lower or equal than the setting value of the upstream server. | no | 100 |
| maxHeadersSize | number | The maximum size of a header block in bytes. | no | 16384 |
| useHuffmanEncoding | boolean | Whether to compress header strings with Huffman Coding. | no | true |
| closeWaitTimeout | number | The close-wait timeout for closed streams in seconds. | no | 5 |
| disablePromiseRequestLog | boolean | Disable request log of server push requests. | no | `value of disableDefaultRequestLog` |

### Upstream server protocol configuration

To actually enable proxying HTTP/2, the upstream server must support HTTP/2 and be marked as such in the configuration.

With the default configuration of a single upstream server, this is done by adding the string "http/2" to the array `upstreamServerProtocols` in the proxy configuration. If a plugin is used that may select a different upstream server, the configuration is likely also different (see the respective plugin documentation on how to add "http/2" as a supported protocol).

If an upstream server is selected that is not marked as supporting HTTP/2, a stream error with error code *HTTP_1_1_REQUIRED* is returned to the client. Modern browsers will retry the request with HTTP/1.1 in that case.


