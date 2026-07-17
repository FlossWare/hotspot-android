package hev.htproxy

open class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
}
