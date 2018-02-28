package net.syncthing.lite.dialogs

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.databinding.DataBindingUtil
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import net.syncthing.java.core.beans.DeviceId
import net.syncthing.lite.R
import net.syncthing.lite.databinding.DialogDeviceIdBinding
import org.jetbrains.anko.doAsync


class DeviceIdDialog(private val context: Context, private val deviceId: DeviceId) {

    private val Tag = "DeviceIdDialog"

    private val binding = DataBindingUtil.inflate<DialogDeviceIdBinding>(
            LayoutInflater.from(context), R.layout.dialog_device_id, null, false)

    fun show() {
        generateQrCode()
        binding.deviceId.text = deviceId.deviceId
        // Make QR code/progress bar views rectangular based on match_parent height.
        binding.progressBar.post {
            binding.progressBar.minimumHeight = binding.progressBar.width
            binding.qrCode.minimumHeight = binding.progressBar.width
        }
        binding.deviceId.setOnClickListener({ copyDeviceId() })
        binding.share.setOnClickListener {  }
        binding.share.setOnClickListener({ shareDeviceId() })

        val qrCodeDialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.device_id))
                .setView(binding.root)
                .setPositiveButton(android.R.string.ok, null)
                .create()

        qrCodeDialog.show()
    }

    private fun generateQrCode() {
        doAsync {
            val writer = QRCodeWriter()
            try {
                val bitMatrix = writer.encode(deviceId.deviceId, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
                async(UI) {
                    binding.progressBar.visibility = View.GONE
                    binding.qrCode.visibility = View.VISIBLE
                    binding.qrCode.setImageBitmap(bmp)
                }
            } catch (e: WriterException) {
                Log.w(Tag, e)
            }
        }
    }

    private fun copyDeviceId() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.device_id), deviceId.deviceId)
        clipboard.primaryClip = clip
        Toast.makeText(context, context.getString(R.string.device_id_copied), Toast.LENGTH_SHORT)
                .show()
    }

    private fun shareDeviceId() {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_TEXT, deviceId.deviceId)
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_device_id_chooser)))
    }
}