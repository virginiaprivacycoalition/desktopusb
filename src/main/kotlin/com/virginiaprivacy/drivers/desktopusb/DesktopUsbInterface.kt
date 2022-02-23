package com.virginiaprivacy.drivers.desktopusb

import com.virginiaprivacy.drivers.sdr.RTLDevice
import com.virginiaprivacy.drivers.sdr.usb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.apache.commons.collections4.queue.CircularFifoQueue
import org.usb4java.*
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.util.logging.Logger
import kotlin.system.exitProcess
import java.util.concurrent.*
import java.util.concurrent.CancellationException

class DesktopUsbInterface(
    private val handle: DeviceHandle,
    private val descriptor: DeviceDescriptor, private var debugEnabled: Boolean = false
) :
    UsbIFace {

    private val availableTransfers = LinkedTransferQueue<Transfer>()


    private val completedTransfers = CircularFifoQueue<Transfer>(32)

    private val completedControlTransfers = CircularFifoQueue<Transfer>()

    private val controlMutex = Mutex()

    private val logger = Logger.getLogger(this::class.qualifiedName)


    private val callback = TransferCallback { transfer ->
        when (transfer.status()) {
            LibUsb.TRANSFER_COMPLETED -> {
                val read = transfer.actualLength()
                val bytes = ByteArray(read)
                transfer.setBuffer(transfer.buffer().position(read))
                transfer.buffer().run {
                    rewind()
                    get(bytes)
                    rewind()
                }
                availableTransfers.add(transfer)
                submitBulkTransfer()
                if (!RTLDevice.bytes.isClosedForSend)
                    RTLDevice.bytes.trySend(bytes)
                        .onSuccess {  return@TransferCallback }
                        .onClosed { return@TransferCallback }
                        .onFailure { clearStalledTransfer(transfer) }
            }
            LibUsb.TRANSFER_STALL -> {
                clearStalledTransfer(transfer)
            }
            LibUsb.TRANSFER_CANCELLED -> {
                availableTransfers.add(transfer)
                submitTransfer()
            }
            else -> {
                val setupPacket = LibUsb.controlTransferGetSetup(transfer)
                val msg = ("""Error with control transfer: Error code """ + transfer.status() + """,
                                | """ + LibUsb.errorName(transfer.status()) + """
                                |
                                |   SETUP PACKET:
                                |   request type: """ + setupPacket.bmRequestType() + """
                                |   request: """ + setupPacket.bRequest() + """
                                |   index: """ + setupPacket.wIndex() + """
                                |   length: """ + setupPacket.wLength() + """
                                |   value: """ + setupPacket.wValue() + """
                                |
                            """).trimMargin()
                transfer.setUserData(msg)
                println(msg)
                completedTransfers.add(transfer)
            }
        }
    }

    private fun clearStalledTransfer(transfer: Transfer) {
        availableTransfers.filter { it.status() != LibUsb.TRANSFER_COMPLETED }
            .onEach { LibUsb.cancelTransfer(it) }
        LibUsb.clearHalt(handle, 0x81.toByte())
        transfer.let { availableTransfers.add(it) }
        submitTransfer()
    }


    private val controlCallback = TransferCallback { transfer ->
        println("Control transfer: $transfer")
        transfer.status().let {
            when (it == LibUsb.TRANSFER_COMPLETED) {
                true -> {
                    val read = transfer.actualLength()
                    transfer.buffer().position(read)
                    completedControlTransfers.add(transfer)
                    return@TransferCallback
                }
                else -> {
                    val setupPacket = LibUsb.controlTransferGetSetup(transfer)
                    val msg = """Error with control transfer: Error code ${transfer.status()},
                    | ${LibUsb.errorName(transfer.status())}
                    |
                    |   SETUP PACKET:
                    |   request type: ${setupPacket.bmRequestType()}
                    |   request: ${setupPacket.bRequest()}
                    |   index: ${setupPacket.wIndex()}
                    |   length: ${setupPacket.wLength()}
                    |   value: ${setupPacket.wValue()}
                    |
                """.trimMargin()
                    transfer.setUserData(msg)
                    println(msg)
                    completedTransfers.add(transfer)
                    return@TransferCallback
                }
            }
        }
    }

    private val eventHandler = Executors.newFixedThreadPool(1).asCoroutineDispatcher()


    override val manufacturerName: String
        get() = LibUsb.getStringDescriptor(handle, descriptor.iManufacturer())
    override val productName: String
        get() = LibUsb.getStringDescriptor(handle, descriptor.iProduct())
    override val serialNumber: String
        get() = LibUsb.getStringDescriptor(handle, descriptor.iSerialNumber())


    override suspend fun bulkTransfer(bytes: ByteArray, length: Int): Int {
        TODO("Not yet implemented")
    }

    override fun claimInterface() {
        if (LibUsb.kernelDriverActive(handle, 0) == 1) {
            println("Kernel driver is active. Attempting to detach...")
            LibUsb.setAutoDetachKernelDriver(handle, true)
        }
        handle
        val r = LibUsb.claimInterface(handle, 0)
        if (r != 0) {
            throw LibUsbException("Error claiming interface!", r)
        }
    }

    init {
//        if (debugEnabled) {
//            LibUsb.setOption(null, LibUsb.OPTION_LOG_LEVEL, LibUsb.LOG_LEVEL_DEBUG)
//        }
    }

    override fun controlTransfer(
        direction: Int,
        address: Short,
        index: Short,
        bytes: ByteBuffer,
    ): ControlTransferResult {



        when (val r = LibUsb.controlTransfer(handle, direction.toByte(), 0, address, index, bytes,
            RTLDevice.CTRL_TIMEOUT.toLong()
        )) {
            in (0..Int.MAX_VALUE) -> {
                return ControlTransferResult(
                    ControlTransferDirection.get(direction.toByte()),
                    ResultStatus.Success,
                    ControlPacket(
                        ByteArray(bytes.position()).apply {
                            bytes.rewind()
                            bytes.get(this)

                        }
                    )
                )
            }
            in (arrayOf(LibUsb.ERROR_PIPE, LibUsb.ERROR_TIMEOUT)) -> {
                try {
                    log("Halt result: ${LibUsb.clearHalt(handle, 0x00)}")
                    val r2 =
                        LibUsb.controlTransfer(handle, direction.toByte(), 0, address, index, bytes, RTLDevice.CTRL_TIMEOUT.toLong())
                    if (r2 > 0) {
                        return ControlTransferResult(
                            ControlTransferDirection.get(direction.toByte()),
                            ResultStatus.Success,
                            ControlPacket(
                                ByteArray(8)
                            )
                        )
                    } else {
                        println(r2)
                        throw LibUsbException(r2)
                    }
                } catch (e: java.lang.Exception) {
                    log("Transfer direction: ${direction
                        .toByte()} transfer address: ${address} transfer index: $index")
                    val length = bytes.capacity()
                    val inout = ByteArray(length)
                    bytes.rewind()
                    bytes.get(inout)
                    bytes.rewind()
                    log("Transfer bytes: ${inout.asList()}")
                    return ControlTransferResult(ControlTransferDirection.get(direction.toByte()), ResultStatus.Success, ControlPacket(ByteArray(8)))
                }

            }
           else -> {
               if (r < 0) {
                   throw java.lang.RuntimeException("Control transfer refused")
               }
               return ControlTransferResult(
                   ControlTransferDirection.get(direction.toByte()),
                   ResultStatus.Success,
                   ControlPacket(
                       ByteArray(bytes.position()).apply {
                           bytes.rewind()
                           bytes.get(this)
                       }
                   )
               )
           }
        }

    }

    override fun prepareNewBulkTransfer(byteBuffer: ByteBuffer) {
        val transfer = LibUsb.allocTransfer()
        LibUsb.fillBulkTransfer(
            transfer, handle,
            0x81.toByte(), byteBuffer, callback, null,
            1000000L
        )
        availableTransfers.add(transfer)
    }

    override fun readBytes() = flow<ByteArray> {
            while (currentCoroutineContext().isActive) {
                val t = completedTransfers.poll() ?: continue
                val b = t.buffer()
                val bytes = ByteArray(b.position())
                b.rewind()
                b[bytes]
                b.rewind()

                availableTransfers.add(t)
                submitTransfer()
                emit(bytes)

        }


    }
        .onStart {
            log("USB data flow collection started")
            if (availableTransfers.isEmpty()) {
                log("Available transfers was empty. Completed transfers size: ${completedTransfers.size}")
                repeat(15) {
                    prepareNewBulkTransfer(ByteBuffer.allocateDirect(RTLDevice.BUF_BYTES))
                }
            }
            coroutineScope {  }
            CoroutineScope(eventHandler).launch {
                while (this.isActive) {
                    log("Available transfer count: ${availableTransfers.size}, Completed transfer count: ${completedTransfers.size}")
                    val r = LibUsb.handleEventsCompleted(null, null)
                    if (r != LibUsb.SUCCESS) {
                        throw IllegalStateException("Unable to handle libusb events: ${LibUsb.strError(r)}")
                    }
                }
            }
        }
        .onCompletion {
            cleanUpTransfers()
            eventHandler.job.cancelAndJoin()
        }
        .catch {
            log("Suppressed exception during USB stream: ${it.message}")
            repeat(15) {
                prepareNewBulkTransfer(ByteBuffer.allocateDirect(RTLDevice.BUF_BYTES))
            }
        }
        .cancellable()


    private fun cleanUpTransfers() {
        availableTransfers.forEach {
            LibUsb.cancelTransfer(it)
        }
        completedTransfers
            .forEach {
                LibUsb.cancelTransfer(it)
                availableTransfers.add(it)
            }
    }


    override fun releaseUsbDevice() {
        val transfers = mutableListOf<Transfer>()
        transfers.addAll(availableTransfers)
        transfers.addAll(completedTransfers)
        availableTransfers.clear()
        completedTransfers.clear()

        try {
            transfers.forEach {
                LibUsb.cancelTransfer(it)
                LibUsb.freeTransfer(it)
            }
        } catch (e: Throwable) {
            println(e.message)
        } finally {
            LibUsb.releaseInterface(handle, 0)
        }
    }


    override fun resetDevice() {
        log("Resetting USB device handle.")
        LibUsb.resetDevice(handle)
    }

    override fun shutdown() {
        availableTransfers.forEach {
            LibUsb.cancelTransfer(it)
            LibUsb.freeTransfer(it)
        }
        completedTransfers.forEach {
            LibUsb.cancelTransfer(it)
            LibUsb.freeTransfer(it)
        }
        eventHandler.cancel()
        LibUsb.close(handle)
    }

    private fun submitTransfer() {
        LibUsb.submitTransfer(availableTransfers.remove())
    }

    private fun log(message: String) {
        if (debugEnabled) {
            logger.info(message)
        }
    }

    override fun submitBulkTransfer() = availableTransfers.take()
        .run {
            val result = LibUsb.submitTransfer(this)
            if (result != LibUsb.SUCCESS) {
                throw
                CancellationException(
                    "Error submitting transfer: ${
                        LibUsb.errorName(
                            result
                        )
                    }"
                )
            }
        }


    companion object {
        const val CONTROL_OUT = LibUsb.ENDPOINT_OUT.toInt() or LibUsb.REQUEST_TYPE_VENDOR.toInt()
        const val BULK_IN = LibUsb.ENDPOINT_IN.toInt() or LibUsb.TRANSFER_TYPE_BULK.toInt()
        const val CONTROL_IN = LibUsb.ENDPOINT_IN.toInt() or LibUsb.REQUEST_TYPE_VENDOR.toInt()

        fun findDevice(vendorID: Short, productID: Short, debug: Boolean = false): DesktopUsbInterface {
            LibUsb.init(null)
            val handle = LibUsb.openDeviceWithVidPid(null, vendorID, productID)
                ?: throw RuntimeException(
                    """No device with vendor ID $vendorID and product ID $productID found.
                            | Check your device's connection and ensure you have permission to open the device.
                        """.trimMargin()
                )
            val device = LibUsb.getDevice(handle)
            val descriptor = DeviceDescriptor()
            LibUsb.getDeviceDescriptor(device, descriptor)
            return DesktopUsbInterface(handle, descriptor, debug)
        }

        fun findAnyAvailableDevice(): DesktopUsbInterface? {
            LibUsb.init(null)
            val deviceList = DeviceList()
            LibUsb.getDeviceList(null, deviceList)
            var descriptor: DeviceDescriptor
            deviceList.mapNotNull {
                descriptor = DeviceDescriptor()
                val r = LibUsb.getDeviceDescriptor(it, descriptor)
                if (r != LibUsb.SUCCESS) {
                   null
                }

                val handle = DeviceHandle()
                val result = LibUsb.open(it, handle)
                if (result != LibUsb.SUCCESS) {
                    null
                } else {
                    LibUsb.freeDeviceList(deviceList, true)
                    descriptor = DeviceDescriptor()
                    LibUsb.getDeviceDescriptor(it, descriptor)

                   return DesktopUsbInterface(handle, descriptor)
                }
            }
            println("No usable RTL-SDR device found. Use device ids from the following list of all accessible devices:")
            deviceList.mapNotNull {
                val d = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(it, d) == LibUsb.SUCCESS) {
                    val buf = ByteArray(d.buffer.remaining())
                    d.buffer.get(buf, d.iManufacturer().toInt(), d.buffer.capacity() - d.iManufacturer().toInt())
                    "${d.idVendor()}:${d.idProduct()}:${buf.decodeToString()}"
                } else {
                    null
                }
            }
                .forEach {
                    val vID = it.split(":")[0]
                    val pID = it.split(":")[1]
                    val ds = it.split(":")[2]
                    println("VendorID: $vID, ProductID: $pID: $ds")
                }
            LibUsb.freeDeviceList(deviceList, true)
            exitProcess(-1)
        }
    }
}

