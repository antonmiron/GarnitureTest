package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.garnituretest.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.IllegalStateException
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private var micPermissionGranted = false
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ _->
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val createFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){ uri ->
        job?.cancel()
        job = lifecycleScope.launch{
            playAudio(uri, viewBinding.editTextNumber.text.toString().toShortOrNull()?:1)
        }
    }

    private lateinit var viewBinding: ActivityMainBinding
    private var job: Job? = null

    private val buffer = ShortArray(636630)
    private var offset = 0

    private val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val audioManager: AudioManager by lazy{ getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val adapter = Adapter()

    private val mediaSessionCallback: MediaSession.Callback = object: MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {

            val mediaButtonAction = mediaButtonIntent.action
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

            adapter.addLog("onMediaButton action: $mediaButtonAction")
            adapter.addLog("onMediaButton key event: $event")

            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            adapter.addLog("onCustomAction action: $action")
            adapter.addLog("onCustomAction extras: $extras")
        }

        override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
            super.onCommand(command, args, cb)
            adapter.addLog("onCommand command: $command")
            adapter.addLog("onCommand args: $args")
            adapter.addLog("onCommand cb: $cb")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        prepareUI()

        createAudioTrack().apply {
            play()
            release()
        }

        var mediaSession: MediaSession? = null
        val bluetoothProfileListener = object: BluetoothProfile.ServiceListener{
            override fun onServiceConnected(p0: Int, p1: BluetoothProfile?) {
                adapter.addLog("onServiceConnected p0:$p0, p1:$p1")

                mediaSession = MediaSession(this@MainActivity, "ANTON")
                mediaSession?.isActive = true
                mediaSession?.setCallback(mediaSessionCallback)
            }

            override fun onServiceDisconnected(p0: Int) {
                adapter.addLog("onServiceDisconnected p0:$p0")
                mediaSession?.release()
            }

        }
//        BluetoothAdapter.getDefaultAdapter().getProfileProxy(this, bluetoothProfileListener, BluetoothProfile.A2DP)
    }


    private fun prepareUI(){
        with(viewBinding){
            /** LOGS RV **/
            rvLogs.adapter = adapter

            /** LOGS COPY BTN **/
            btnCopy.setOnClickListener {
                val logs = adapter.getLogs().joinToString("\n")
                val clipboardService = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardService.setPrimaryClip(ClipData.newPlainText("logs", logs))
                Toast.makeText(this@MainActivity, "Logs copied!", Toast.LENGTH_SHORT).show()
            }

            /**REC**/
            btnRec.setOnClickListener {
                job?.cancel()

                if(!micPermissionGranted){
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnClickListener
                }

                audioManager.startBluetoothSco()

                job = lifecycleScope.launch {
                    viewBinding.tvStatus.text = "RECORDING"
                    recAudio()
                    viewBinding.tvStatus.text = "STOPPED"
                }

                Log.d("ANTON","audioManager.isBluetoothScoOn: ${audioManager.isBluetoothScoOn}")
            }

            /**STOP**/
            btnStop.setOnClickListener {
                try {
                    audioRecord?.stop()
                    audioTrack?.stop()
                } catch (ex: IllegalStateException){}

                audioManager.stopBluetoothSco()

                job?.cancel()
                job = lifecycleScope.launch {
                    tvStatus.text = "STOP"
                }
            }

            /**PLAY**/
            btnPlay.setOnClickListener {
                job?.cancel()
                job = lifecycleScope.launch {
//                    viewBinding.tvStatus.text = "PLAY"
//                    playAudio()
//                    viewBinding.tvStatus.text = "STOP"
                    createFileLauncher.launch(null)
                }
            }
        }
    }



    private suspend fun recAudio() = withContext(Dispatchers.IO){
        try {
            audioRecord = createAudioRecord()

            audioRecord?.startRecording()
            Log.d("ANTON", "audioRecord: $audioRecord")

            offset = 0
            while (isActive && offset < buffer.size) {
                offset+= audioRecord?.read(buffer, offset, 160)?:0
            }

        }
        catch (ex: CancellationException){ }
        finally { audioRecord?.release() }
    }

    private suspend fun playAudio(uri:Uri, gainCoefficient: Short) = withContext(Dispatchers.IO){
        val inputStream = resources.openRawResource(R.raw.source)
        val outputStream = getOutputForFile(uri)

        try {
            audioTrack = createAudioTrack()
            Log.d("ANTON", "audioTrack: $audioTrack")

            for(i in 0 until offset){
                val old = buffer[i]
                buffer[i] = min(old * gainCoefficient, Short.MAX_VALUE.toInt()).toShort()
                val new = buffer[i]
                if(i<10) Log.d("ANTON","old: $old, new: $new")
            }

            val wave = Wave(8000, 1, buffer, 0, offset)
            val result = wave.wroteToFile(outputStream)
            Log.d("ANTON", "result: $result")

            audioTrack?.play()
            audioTrack?.write(buffer, 0, buffer.size)

//            val byteArray = ByteArray(630000)
//            val offset = inputStream.read(byteArray)
//            val shortArray = ShortArray(byteArray.size / 2) {
//                (byteArray[it * 2].toUByte().toInt() + (byteArray[(it * 2) + 1].toInt() shl 8)).toShort()
//            }.onEach {
//                 it*gainCoefficient
//            }
//            val wave = Wave(8000, 1, shortArray, 0, offset)
//            val result = wave.wroteToFile(outputStream)
//            Log.d("ANTON", "result: $result")

        }
        catch (ex: CancellationException){ }
        finally {
            delay(1000)
            audioTrack?.release()
            outputStream?.close()
        }
    }

    private suspend fun getOutputForFile(uri: Uri):OutputStream? {
        val dir = DocumentFile.fromTreeUri(this, uri)
        val file = dir?.createFile("audio/x-wav", "qwe.wav")?:return null
        return contentResolver.openOutputStream(file.uri)
    }
    fun toBytes(s: Short): ByteArray {
        return byteArrayOf((s.toInt() and 0x00FF).toByte(), ((s.toInt() and 0xFF00) shr (8)).toByte())
    }



    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord{
        val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            8000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 10)
    }

    private fun createAudioTrack(): AudioTrack{
        val attributesBuilder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)

        val formatBuilder = AudioFormat.Builder()
            .setSampleRate(8000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)

        return AudioTrack(
            attributesBuilder.build(),
            formatBuilder.build(),
            minBufferSize * 10,
            AudioTrack.MODE_STREAM,
            0)
    }
}