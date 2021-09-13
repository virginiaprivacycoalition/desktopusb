package com.virginiaprivacy.drivers.desktopusb

import com.virginiaprivacy.drivers.sdr.usb.UsbIFace
import org.usb4java.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit

class DesktopUsbInterface(
    private val device: Device,
    private val handle: DeviceHandle,
    private val descriptor: DeviceDescriptor
) :
    UsbIFace {

    private val log = System.getLogger(this::class.java.name)

    private val availableTransfers = LinkedTransferQueue<Transfer>()

    private val completedTransfers = LinkedTransferQueue<Transfer>()

    private val callback = TransferCallback { transfer ->
        if (transfer.status() != LibUsb.SUCCESS) {
            println("Error with transfer: ${LibUsb.errorName(transfer.status())}")
            return@TransferCallback
        }
        val read = transfer.actualLength()
        transfer.setBuffer(transfer.buffer().position(read) as ByteBuffer)
        completedTransfers.put(transfer)
    }


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
        val r = LibUsb.claimInterface(handle, 0)
        if (r != 0) {
            throw LibUsbException("Error claiming interface!", r)
        }

    }

    override fun controlTransfer(
        direction: Int,
        requestID: Int,
        address: Int,
        index: Int,
        bytes: ByteArray,
        length: Int,
        timeout: Int
    ): Int {
        return if (direction == CONTROL_OUT) {
            val buffer = ByteBuffer.allocateDirect(length)
            if (bytes.size > length) {
                throw IOException("Length $length is not big enough to hold buffer with size ${bytes.size}")
            }
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(bytes)
            LibUsb.controlTransfer(
                handle,
                direction.toByte(),
                requestID.toByte(),
                address.toShort(),
                index.toShort(),
                buffer,
                300
            )
        } else {
            val buffer = ByteBuffer.allocateDirect(length)
            val read = LibUsb.controlTransfer(
                handle,
                direction.toByte(),
                requestID.toByte(),
                address.toShort(),
                index.toShort(),
                buffer,
                300
            )
            if (read > 0) {
                buffer.rewind()
                buffer.get(bytes)
            }
            read
        }
    }


    override suspend fun prepareNewBulkTransfer(transferIndex: Int, byteBuffer: ByteBuffer) {
        val transfer = LibUsb.allocTransfer()
        LibUsb.fillBulkTransfer(
            transfer, handle,
            0x81.toByte(), byteBuffer, callback, transferIndex,
            1000000L
        )
        availableTransfers.put(transfer)
    }

    override fun releaseUsbDevice() {
        val transfers = mutableListOf<Transfer>()
        availableTransfers.drainTo(transfers)
        completedTransfers.drainTo(transfers)
        try {
            transfers.forEach {
                LibUsb.freeTransfer(it)
            }
        } catch (e: Throwable) {
            println(e.message)
        } finally {
            LibUsb.releaseInterface(handle, 0)
        }
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
        LibUsb.close(handle)
    }

    override suspend fun submitBulkTransfer(buffer: ByteBuffer) {
        availableTransfers.poll(200, TimeUnit.MILLISECONDS)?.let {
            it.setBuffer(buffer)
            val result = LibUsb.submitTransfer(it)
            if (result != LibUsb.SUCCESS) {
                throw IOException("Error submitting transfer: ${LibUsb.errorName(result)}")
            }
        }
    }

    override suspend fun waitForTransferResult(): Int {
        val r = LibUsb.handleEventsCompleted(null, null)
        if (r != LibUsb.SUCCESS) {
            throw IOException(LibUsb.errorName(r))
        }
        completedTransfers.poll(1000, TimeUnit.MILLISECONDS)?.let {
            val result = it.userData() as Int
            availableTransfers.put(it)
            return result
        }
        throw IOException()
    }


    companion object {
        const val CONTROL_OUT = LibUsb.ENDPOINT_OUT.toInt() or LibUsb.REQUEST_TYPE_VENDOR.toInt()
        const val BULK_IN = LibUsb.ENDPOINT_IN.toInt() or LibUsb.TRANSFER_TYPE_BULK.toInt()
        const val CONTROL_IN = LibUsb.ENDPOINT_IN.toInt() or LibUsb.REQUEST_TYPE_VENDOR.toInt()

        fun findDevice(vendorID: Short, productID: Short): DesktopUsbInterface {
            LibUsb.init(null)
            val deviceList = DeviceList()
            LibUsb.getDeviceList(null, deviceList)
            var descriptor: DeviceDescriptor
            deviceList.firstOrNull {
                descriptor = DeviceDescriptor()
                val r = LibUsb.getDeviceDescriptor(it, descriptor)
                if (r != LibUsb.SUCCESS) {

                }
                descriptor.idVendor() == vendorID && descriptor.idProduct() == productID
            }?.let {
                val handle = DeviceHandle()
                val result = LibUsb.open(it, handle)
                if (result != LibUsb.SUCCESS) {
                    throw LibUsbException(result)
                }
                LibUsb.freeDeviceList(deviceList, true)
                descriptor = DeviceDescriptor()
                LibUsb.getDeviceDescriptor(it, descriptor)
                return DesktopUsbInterface(device = it, handle, descriptor)
            }
            LibUsb.freeDeviceList(deviceList, true)
            throw IOException("No devices found.")
        }
    }
}