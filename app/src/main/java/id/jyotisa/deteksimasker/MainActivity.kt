package com.example.aps1

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import id.jyotisa.deteksimasker.Box
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.face.FaceDetector
import kotlinx.android.synthetic.main.activity_main.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import android.graphics.YuvImage as YuvImage1


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraView.setLifecycleOwner(this)

        // Create a FaceDetector
        val faceDetector = FaceDetector.Builder(this).setTrackingEnabled(true).build()

        cameraView.addFrameProcessor{ frame ->
            val matrix = Matrix()
            Log.v("status", "start");
            matrix.setRotate(frame.rotationToUser.toFloat())

            if (frame.dataClass === ByteArray::class.java){
                val out = ByteArrayOutputStream()
                val yuvImage = YuvImage1(
                    frame.getData(),
                    ImageFormat.NV21,
                    frame.size.width,
                    frame.size.height,
                    null
                )
                yuvImage.compressToJpeg(
                    Rect(0, 0, frame.size.width, frame.size.height), 100, out
                )
                val imageBytes = out.toByteArray()
                var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap = Bitmap.createScaledBitmap(bitmap, overlayView.width, overlayView.height, true)

                overlayView.boundingBox = processBitmap(bitmap, faceDetector)
                overlayView.invalidate()
            } else {
                Toast.makeText(this, "Kamera anda tidak mendukung", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processBitmap(bitmap: Bitmap, faceDetector: FaceDetector): MutableList<Box>{
        Log.v("status", "start bitmap");
        val boundingBoxList = mutableListOf<Box>()

        // Detect the faces
        val frame = Frame.Builder().setBitmap(bitmap).build()
        Log.v("status", "detect frame");
        val faces = faceDetector.detect(frame)
        Log.v("status", "selesai detect frame");
        // Mark out the identified face
        for (i in 0 until faces.size()) {
            val thisFace = faces.valueAt(i)
            val left = thisFace.position.x
            val top = thisFace.position.y
            val right = left + thisFace.width
            val bottom = top + thisFace.height
            val bitmapCropped = Bitmap.createBitmap(bitmap,
                left.toInt(),
                top.toInt(),
                if (right.toInt() > bitmap.width) {
                    bitmap.width - left.toInt()
                } else {
                    thisFace.width.toInt()
                },
                if (bottom.toInt() > bitmap.height) {
                    bitmap.height - top.toInt()
                } else {
                    thisFace.height.toInt()
                })
            Log.v("status", "start predict");
            val label = predict(bitmapCropped)
            Log.v("status", "selesai predict");
            var predictionn = ""
            val with = label["WithMask"]?: 0F
            val without = label["WithoutMask"]?: 0F

            if (with > without){
                predictionn = "With Mask"
                Log.v("status predict", "mask");
            } else {
                predictionn = "Without Mask"
                Log.v("status predict", "no mask");
            }

            boundingBoxList.add(
                Box(
                    RectF(
                        left,
                        top,
                        right,
                        bottom
                    ), predictionn, with > without
                )
            )
        }
        return boundingBoxList
    }

    private fun predict(input: Bitmap): MutableMap<String, Float> {
        // load model
        val modelFile = FileUtil.loadMappedFile(this, "model.tflite")
        val model = Interpreter(modelFile, Interpreter.Options()) 
        val labels = FileUtil.loadLabels(this, "labels.txt")

        // data type
        val imageDataType = model.getInputTensor(0).dataType() 
        val inputShape = model.getInputTensor(0).shape() 

        val outputDataType = model.getOutputTensor(0).dataType() 
        val outputShape = model.getOutputTensor(0).shape() 

        var inputImageBuffer = TensorImage(imageDataType)
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType) 

        // preprocessing
        val cropSize = kotlin.math.min(input.width, input.height)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeWithCropOrPadOp(cropSize, cropSize)) 
            .add(ResizeOp(inputShape[1], inputShape[2], ResizeOp.ResizeMethod.NEAREST_NEIGHBOR)) 
            .add(NormalizeOp(100f, 100f))
            .build()

        // load image
        inputImageBuffer.load(input) 
        inputImageBuffer = imageProcessor.process(inputImageBuffer) 

        // run model
        model.run(inputImageBuffer.buffer, outputBuffer.buffer.rewind())

        // get output
        val labelOutput = TensorLabel(labels, outputBuffer) 

        val label = labelOutput.mapWithFloatValue
        return label
    }

}
