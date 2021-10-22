package tj.rs.devteam.parallaxlayout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent

class ViewMotionHelper(
    val context: Context,
    val lifecycleOwner: LifecycleOwner
) : SensorEventListener {
    companion object {
        private const val MATRIX_SIZE = 16
        private const val DEFAULT_SAMPLING_PERIOD = 100000
        private const val ROTATION_VECTOR_SIZE = 4
        private const val DEFAULT_DURATION = 300L
        private val DEFAULT_INTERPOLATOR = DecelerateInterpolator()

    }

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    init {
        lifecycleOwner.lifecycle.addObserver(SensorLifecycleObserver())
    }

    @Volatile
    private var initialized = false

    private val truncatedRotationVector = FloatArray(ROTATION_VECTOR_SIZE)

    // матрица поворота начального положения устройства в пространстве
    // массив размера 16 нужен для хранения значений матрицы 4х4
    val initialValues = FloatArray(MATRIX_SIZE)

    // буфер для матрицы поворота
    val rotationMatrix = FloatArray(MATRIX_SIZE)

    // буфер вычисления для изменения угла
    val angleChange = FloatArray(MATRIX_SIZE)

    private val viewMotionSpecs = mutableListOf<ViewMotionSpec>()

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // получаем вектор поворота устройства в пространстве
        val rotationVector = getRotationVectorFromSensorEvent(event)

        if (!initialized) {
            initialized = true

            // запоминаем матрицу поворота для начального положения устройства
            SensorManager.getRotationMatrixFromVector(initialValues, rotationVector)
            return
        }

        // получаем матрицу поворота для текущего положения устройства
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)

        // получаем пространственный угол между начальным и текущим положением
        SensorManager.getAngleChange(angleChange, rotationMatrix, initialValues)

        angleChange.forEachIndexed { index, value ->
            angleChange[index] = radianToFraction(value)
        }

        animate()
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }

    /**
     * Map domain of tilt vector from radian (-PI, PI) to fraction (-1, 1)
     */
    private fun radianToFraction(value: Float): Float {
        return (value / Math.PI)
            .coerceIn(-1.0, 1.0)
            .toFloat()
    }

    fun registerView(
        view: View,
        maxTranslation: Int
    ) {
        viewMotionSpecs.add(ViewMotionSpec(view, maxTranslation))
    }

    private fun animate() {
        viewMotionSpecs.forEach { viewMotionSpec ->
            val view = viewMotionSpec.view

            val xTranslation = -angleChange[2] * viewMotionSpec.maxTranslation
            val yTranslation = angleChange[1] * viewMotionSpec.maxTranslation

            view.animate { translationX(xTranslation) }
            view.animate { translationY(yTranslation) }
        }
    }

    private fun View.animate(builder: ViewPropertyAnimator.() -> Unit) {
        animate()
            .apply {
                duration = DEFAULT_DURATION
                interpolator = DEFAULT_INTERPOLATOR
                builder()
            }
            .start()
    }

    private fun getRotationVectorFromSensorEvent(event: SensorEvent): FloatArray {
        return if (event.values.size > ROTATION_VECTOR_SIZE) {
            // On some Samsung devices SensorManager.getRotationMatrixFromVector
            // appears to throw an exception if rotation vector has length > 4.
            // For the purposes of this class the first 4 values of the
            // rotation vector are sufficient (see crbug.com/335298 for details).
            System.arraycopy(event.values, 0, truncatedRotationVector, 0, ROTATION_VECTOR_SIZE)
            truncatedRotationVector
        } else {
            event.values
        }
    }

    private fun registerSensorListener() {
        if (sensorManager == null) return

        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?.also { sensor ->
                sensorManager.registerListener(this, sensor, DEFAULT_SAMPLING_PERIOD)
            }
    }

    private fun unregisterSensorListener() {
        sensorManager?.unregisterListener(this)
        initialized = false
    }

    private fun removeAllViews() {
        viewMotionSpecs.clear()
    }

    private class ViewMotionSpec(
        val view: View,
        val maxTranslation: Int
    )

    private inner class SensorLifecycleObserver : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun onResume() {
            registerSensorListener()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        fun onPause() {
            unregisterSensorListener()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        fun onDestroy() {
            removeAllViews()
        }
    }
}