package com.example.garnituretest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import com.example.garnituretest.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.lang.IllegalStateException
import java.util.*
import java.util.concurrent.CancellationException

class MainActivity : AppCompatActivity() {
    private var micPermissionGranted = false
    private var bluetoothPermissionGranted = false

    @SuppressLint("MissingPermission")
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ _->
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        bluetoothPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        if(bluetoothPermissionGranted){
            startBluetoothAdapter()
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

    private var socket: BluetoothSocket? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        bluetoothPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        prepareUI()

        createAudioTrack().apply {
            play()
            release()
        }

        try{
            startBluetoothAdapter()
        } catch (ex: SecurityException){
            /**
             * сделал через exception потому что для устройств до 11 андроида и target api 30 включительно запрашивать пермишен не нужно
             * достаточно просто выставить в манифесте android.permission.BLUETOOTH.
             * для апи 31+ нужен уже android.permission.BLUETOOTH_CONNECT и запрос на использование
             *
             * я в душе не чаю что там за девайс потому запускаю "в лоб", если свалится по причине того что пермишен не выыдан - запрошу
             * **/
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
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
                    tvStatus.text = "PLAY"
                    playAudio()
                    tvStatus.text = "STOP"
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startBluetoothAdapter(){

        /**
         * Да деприкейтет и правльно делать вот так:
         * val bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
         * bluetoothManager.getAdapter()
         *
         * но пока для простоты, пофиг)
         * **/
        BluetoothAdapter.getDefaultAdapter().bondedDevices.forEach{ device ->
            adapter.addLog("device name: ${device.name}, type: ${device.type}, boundState: ${device.bondState}, bluetoothClass: ${device.bluetoothClass}, uuids: ${device.uuids}")
            if(device.name.startsWith("otto",true)){
                var readJob: Job? = null
                try {
                    /**
                     * Этот UUID "00001101-0000-1000-8000-00805F9B34FB" взят не с потолка
                     * это дефолтный UUID для SPP (serial port profile): https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createRfcommSocketToServiceRecord(java.util.UUID)
                     * **/
                    socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                    readJob = lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            socket?.connect()
                            val inputStream = socket?.inputStream
                            val byteArray = ByteArray(512)

                            withContext(Dispatchers.Main){
                                adapter.addLog("inside coroutine: isActive:$isActive, socket?.isConnected == ${socket?.isConnected == true}")
                            }

                            while(isActive && socket?.isConnected == true){
                                inputStream?.read(byteArray)
                                withContext(Dispatchers.Main){
                                    adapter.addLog("bytes: ${byteArray.joinToString()}")
                                }
                            }
                        } catch (ex: Exception){
                            Log.e("ANTON", ex.stackTraceToString())
                            withContext(Dispatchers.Main) {
                                adapter.addLog("exception in coroutine: ${ex.stackTraceToString()}")
                            }
                        }
                    }
                } catch (ex: Exception){
                    readJob?.cancel()
                    Log.e("ANTON", ex.stackTraceToString())
                    adapter.addLog("exception: ${ex.stackTraceToString()}")
                    return
                }
                return
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
                offset += audioRecord?.read(buffer, offset, 160)?:0
            }
        }
        catch (ex: CancellationException){ }
        finally { audioRecord?.release() }
    }

    private suspend fun playAudio() = withContext(Dispatchers.IO){
        try {
            audioTrack = createAudioTrack()
            Log.d("ANTON", "audioTrack: $audioTrack")
            audioTrack?.play()
            audioTrack?.write(buffer, 0, offset)
        }
        catch (ex: CancellationException){ }
        finally {
            delay(100)
            audioTrack?.release() }
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