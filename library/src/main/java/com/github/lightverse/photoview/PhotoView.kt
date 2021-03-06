package com.github.lightverse.photoview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView

/**
 * Created by lightverse on 2018/4/26.
 */

data class Test(var x:Int)

data class PhotoViewState(
        var enable:Boolean,
        var test: Test? = null,
        var matrix: Matrix? = null)

class PhotoView(context: Context,attr: AttributeSet? = null):ImageView(context,attr), SimpleZoomChangeListener, SimpleTranslateChangeListener {

    val values = FloatArray(9)
    private val photoViewState = PhotoViewState(true)

    private val originScaleType: ScaleType = scaleType

    private var enable:Boolean = false
    set(value) {
        field = value
        scaleType = if(field && scaleType != ScaleType.MATRIX){
            ScaleType.MATRIX
        }else{
            originScaleType
        }
    }

    private val mPhotoMatrix = Matrix()
    private val mLastMatrix = Matrix()

    private var handledBySelf = false

    private var alreadySet = false

    private var hadInit = false

    private val zoomDetector = ZoomDetector(ViewConfiguration.get(context))
    private val translateDetector = TranslateDetector(ViewConfiguration.get(context))

    private var minScale = 1.0f
    private var maxScale = 1.0f

    private var maxZoomScale = 10.0f

    private var mInitScaleX = -1.0f
    private var mInitScaleY = -1.0f


    var bounces = false

    private val bouncesTrans = RectF()
    private val bouncesZoom = PointF()


    private val mLastRawZoomMatrix = Matrix()
    private val mLastRawTranslateMatrix = Matrix()

    private val mLastRealZoomMatrix = Matrix()
    private val mLastRealTranslateMatrix = Matrix()


    private val mTempPointF = PointF()

    private val mTempZoomMatrix = Matrix()
    private val mTempTransMatrix = Matrix()
    private val mTempMatrix = Matrix()


    init {
        enable = true //to trigger enable's set method
        zoomDetector.mZoomChangeListener = this
        translateDetector.mTranslateChangeListener = this
        isClickable = true
    }


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if(!hadInit){
            hadInit = true
            //初始化矩阵为FitCenter
            val dwidth = drawable.intrinsicWidth
            val dheight = drawable.intrinsicHeight

            val vwidth = width - paddingLeft - paddingRight
            val vheight = height - paddingTop - paddingBottom

            drawable.bounds.set(0,0,dwidth,dheight)
            val scale: Float
            var dx = 0f
            var dy = 0f
            mInitScaleX = vheight.toFloat() / dheight.toFloat()
            mInitScaleY = vwidth.toFloat() / dwidth.toFloat()
            if (dwidth * vheight < vwidth * dheight) {
                scale = mInitScaleX
                dx = (vwidth - dwidth * scale) * 0.5f
            } else {
                scale = mInitScaleY
                dy = (vheight - dheight * scale) * 0.5f
            }

            minScale = scale
            maxScale = maxZoomScale * minScale

            bouncesZoom.set(minScale*0.8f,maxScale*1.2f)
            bouncesTrans.set(100.0f,100.0f,100.0f,100.0f)
            val matrix = Matrix()
            matrix.setScale(scale,scale)
            matrix.postTranslate(dx,dy)
            mPhotoMatrix.set(matrix)
            imageMatrix = mPhotoMatrix
        }
        super.onLayout(changed, left, top, right, bottom)
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val oldHandledState = handledBySelf
        handledBySelf = translateDetector.onTouchEvent(event)

        if(!handledBySelf){
            handledBySelf = zoomDetector.onTouchEvent(event)
        }

        //if event begin handle by self, notify super cancel it
        if(oldHandledState != handledBySelf && handledBySelf){
            event.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(event)
            return true
        }

        val result = handledBySelf || super.onTouchEvent(event)

        //reset handled state
        if(event.action == MotionEvent.ACTION_UP){
            handledBySelf = false
        }
        return result
    }

    override fun onZoomEnd() {
        savePhotoMatrix(zoomDetector.mZoomMatrix)
    }

    override fun onZoomChange(matrix: Matrix) {
        //consider the max and min scale
        logMatrix(zoomDetector.mZoomMatrix)

        mLastRawZoomMatrix.set(zoomDetector.mZoomMatrix)

        updatePhotoMatrix(matrix)
    }

    /**
     * update but not save
     */
    private fun updatePhotoMatrix(nextMatrix: Matrix){

        val minScaleWB = bouncesZoom.x
        val maxScaleWB = bouncesZoom.y

        val deltaLeftXWB = bouncesTrans.left
        val deltaRightXWB = bouncesTrans.right
        val deltaBottomXWB = bouncesTrans.bottom
        val deltaTopXWB = bouncesTrans.top


        mTempZoomMatrix.set(mPhotoMatrix)
        mTempZoomMatrix.postConcat(nextMatrix)
        val currentScale = MatrixUtil.getScale(mTempZoomMatrix)

        //check scale
        if(currentScale !in minScaleWB..maxScaleWB){
            mTempZoomMatrix.set(mPhotoMatrix)
            return
        }

        //check trans
        mTempTransMatrix.set(mTempZoomMatrix)

        val endScale = MatrixUtil.getScale(mTempTransMatrix)
        val maxInitScale = Math.max(mInitScaleX,mInitScaleY)

        val deltaLeft = MatrixUtil.getTransX(mTempTransMatrix)
        val deltaTop = MatrixUtil.getTransY(mTempTransMatrix)
        val deltaRight = drawable.intrinsicWidth*endScale + deltaLeft - width
        val deltaBottom = drawable.intrinsicHeight*endScale + deltaTop - height
        var transX = 0f
        var transY = 0f
        if(endScale > maxInitScale){
            //all contains view
            if(deltaLeft > deltaLeftXWB){
                transX = -(deltaLeft - deltaLeftXWB)
            }else if(deltaRight < -deltaRightXWB){
                transX = -(deltaRight + deltaRightXWB)
            }
            if(deltaTop > deltaTopXWB){
                transY = -(deltaTop-deltaTopXWB)
            }else if(deltaBottom < -deltaBottomXWB){
                transY = -(deltaBottom + deltaBottomXWB)
            }
        }else{
            if(maxInitScale == mInitScaleX){//x比例最大,上下可移动,左右必须在view外面
                //all contains view
                if(deltaLeft > deltaLeftXWB){
                    transX = -(deltaLeft - deltaLeftXWB)
                }else if(deltaRight < -deltaRightXWB){
                    transX = -(deltaRight + deltaRightXWB)
                }

                if(deltaTop < 0){
                    transY = -deltaTop
                }else if(deltaBottom > 0){
                    transY = -deltaBottom
                }
            }else{
                if(deltaTop > deltaTopXWB){
                    transY = -(deltaTop-deltaTopXWB)
                }else if(deltaBottom < -deltaBottomXWB){
                    transY = -(deltaBottom + deltaBottomXWB)
                }
                if(deltaLeft < 0){
                    transX = -deltaLeft
                }else if(deltaRight > 0){
                    transX = -deltaRight
                }
            }
        }
        mPhotoMatrix.set(mTempTransMatrix)
        mPhotoMatrix.postTranslate(transX,transY)
        imageMatrix = mPhotoMatrix
    }




    /**
     * save this matrix for next operation(like a down-move-up operation)
     * you should set it after the update
     */
    private fun savePhotoMatrix(nextMatrix: Matrix){

        mLastMatrix.invert(mTempMatrix)
        mTempMatrix.postConcat(mPhotoMatrix)
        //check scale
        val lastScale = MatrixUtil.getScale(mLastMatrix)
        val expectMinScale = minScale/lastScale
        val expectMaxScale = maxScale/lastScale

        val nextScale = MatrixUtil.getScale(mTempMatrix)

        when (nextScale) {
            !in expectMinScale..expectMaxScale -> {
                val scale = if(nextScale <= expectMinScale) expectMinScale else expectMaxScale

                val resultPointF = zoomDetector.getTransPointFByScale(scale,mTempPointF)

                val result = resultPointF?.let {
                    mTempZoomMatrix.setScale(scale,scale)
                    mTempZoomMatrix.postTranslate(it.x,it.y)
                    mTempZoomMatrix
                }

                if(result !is Matrix){
                    mTempZoomMatrix.set(mTempMatrix)
                }

            }
            else -> {
                mTempZoomMatrix.set(mTempMatrix)
            }
        }

        //check trans

        mTempTransMatrix.set(mLastMatrix)
        mTempTransMatrix.postConcat(mTempZoomMatrix)

        val endScale = MatrixUtil.getScale(mTempTransMatrix)
        val maxInitScale = Math.max(mInitScaleX,mInitScaleY)

        val deltaLeft = MatrixUtil.getTransX(mTempTransMatrix)
        val deltaTop = MatrixUtil.getTransY(mTempTransMatrix)
        val deltaRight = drawable.intrinsicWidth*endScale + deltaLeft - width
        val deltaBottom = drawable.intrinsicHeight*endScale + deltaTop - height
        var transX = 0f
        var transY = 0f
        if(endScale > maxInitScale){
            //all contains view
            if(deltaLeft > 0){
                transX = -deltaLeft
            }else if(deltaRight < 0){
                transX = -deltaRight
            }
            if(deltaTop > 0){
                transY = -deltaTop
            }else if(deltaBottom < 0){
                transY = -deltaBottom
            }
        }else{
            if(maxInitScale == mInitScaleX){//x比例最大,上下可移动,左右必须在view外面
                //all contains view
                if(deltaLeft > 0){
                    transX = -deltaLeft
                }else if(deltaRight < 0){
                    transX = -deltaRight
                }

                if(deltaTop < 0){
                    transY = -deltaTop
                }else if(deltaBottom > 0){
                    transY = -deltaBottom
                }
            }else{
                if(deltaTop > 0){
                    transY = -deltaTop
                }else if(deltaBottom < 0){
                    transY = -deltaBottom
                }
                if(deltaLeft < 0){
                    transX = -deltaLeft
                }else if(deltaRight > 0){
                    transX = -deltaRight
                }
            }
        }

        mLastMatrix.postConcat(mTempZoomMatrix)
        mLastMatrix.postTranslate(transX,transY)
        mPhotoMatrix.set(mLastMatrix)
        mLastMatrix.set(mPhotoMatrix)
        imageMatrix = mPhotoMatrix
    }

    override fun onTranslate(matrix: Matrix) {
        updatePhotoMatrix(matrix)
    }

    override fun onTranslateEnd() {
        savePhotoMatrix(translateDetector.mTranslateMatrix)
    }


    private fun logMatrix(matrix:Matrix){
        Log.e("logMatrix",matrix.toString())
    }

    fun snapshot(): PhotoViewState = photoViewState.mutableCopy()

}

fun PhotoViewState.mutableCopy(): PhotoViewState = copy(test = test?.copy(),matrix = matrix?.mutableCopy())

fun Matrix.mutableCopy() = Matrix(this)