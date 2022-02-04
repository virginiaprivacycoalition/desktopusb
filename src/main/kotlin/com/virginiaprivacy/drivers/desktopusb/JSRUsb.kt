//package com.virginiaprivacy.drivers.desktopusb
//
//import com.virginiaprivacy.drivers.sdr.usb.ControlTransferResult
//import com.virginiaprivacy.drivers.sdr.usb.UsbIFace
//import kotlinx.coroutines.flow.Flow
//import org.usb4java.LibUsb
//import java.io.IOException
//import java.nio.ByteBuffer
//import java.util.concurrent.LinkedTransferQueue
//import java.util.concurrent.TransferQueue
//import javax.usb.*
//import javax.usb.event.UsbPipeDataEvent
//import javax.usb.event.UsbPipeErrorEvent
//import javax.usb.event.UsbPipeListener
//
//
//class JSRUsb(val device: UsbDevice) : UsbIFace {
//
//    companion object {
//        fun findDevice(hub: UsbHub, vendorId: Short, productId: Short): JSRUsb {
//            var dev: UsbDevice? = null
//            for (device in hub!!.attachedUsbDevices.filterNotNull().map { it as UsbDevice }) {
//                val desc = device.usbDeviceDescriptor
//                if (desc.idVendor() == vendorId && desc.idProduct() == productId) return JSRUsb(device)
//                if (device.isUsbHub) {
//                    return findDevice(device as UsbHub, vendorId, productId)
//
//                }
//            }
//            throw IOException("No valid devices found.")
//        }
//    }
//
//    lateinit var usbInterface: UsbInterface
//
//    lateinit var pipe: UsbPipe
//
//    private val transferQueue = LinkedTransferQueue<Pair<UsbIrp, ByteBuffer>>()
//
//    override val manufacturerName: String
//        get() = device.manufacturerString
//    override val productName: String
//        get() = device.productString
//    override val serialNumber: String
//        get() = device.serialNumberString
//
//    override suspend fun bulkTransfer(bytes: ByteArray, length: Int): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun claimInterface() {
//        usbInterface = device
//            .activeUsbConfiguration
//            .usbInterfaces
//            .filterNotNull()
//            .map { it as UsbInterface }
//            .filter { !it.usbEndpoints.isNullOrEmpty() }
//            .first()
//            .also {
//                it.claim()
//            }.also {
//                pipe = it
//                    .usbEndpoints
//                    .filterNotNull()
//                    .map { it as UsbEndpoint }
//                    .first()
//                    .usbPipe
//                    .apply { open() }
//            }
//    }
//
//    override fun controlTransfer(
//        direction: Int,
//        address: Short,
//        index: Short,
//        bytes: ByteBuffer,
//        length: Int,
//        timeout: Int
//    ): ControlTransferResult {
//        TODO("Not yet implemented")
//    }
//
//    override suspend fun prepareNewBulkTransfer(transferIndex: Int, byteBuffer: ByteBuffer) {
//        pipe.addUsbPipeListener(object : UsbPipeListener {
//            override fun errorEventOccurred(event: UsbPipeErrorEvent?) {
//                TODO("Not yet implemented")
//            }
//
//            override fun dataEventOccurred(event: UsbPipeDataEvent?) {
//                event!!.usbIrp
//            }
//
//        })
//    }
//
//    override fun readBytes(): Flow<ByteArray> {
//        TODO("Not yet implemented")
//    }
//
//    override fun releaseUsbDevice() {
//        TODO("Not yet implemented")
//    }
//
//    override fun resetDevice() {
//        TODO("Not yet implemented")
//    }
//
//    override fun shutdown() {
//        TODO("Not yet implemented")
//    }
//
//    override suspend fun submitBulkTransfer(buffer: ByteBuffer) {
//        val bytes = ByteArray(buffer.capacity())
//        transferQueue.put(pipe.asyncSubmit(bytes) to buffer)
//        pipe
//    }
//
//    override suspend fun waitForTransferResult(): Int {
//        transferQueue.take().first
//        LibUsb.REQUEST_TYPE_VENDOR
//        return 0
//    }
//}