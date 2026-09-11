package org.olcbox.app.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureAutoFocusRangeRestrictionNear
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.minimumFocusDistance
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.torchMode
import platform.AVFoundation.videoZoomFactor
import platform.AVFoundation.focusMode
import platform.AVFoundation.exposureMode
import platform.AVFoundation.autoFocusRangeRestriction
import platform.AVFoundation.isAutoFocusRangeRestrictionSupported
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isSmoothAutoFocusSupported
import platform.AVFoundation.smoothAutoFocusEnabled
import platform.AVFoundation.focusPointOfInterestSupported
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.exposurePointOfInterestSupported
import platform.AVFoundation.exposurePointOfInterest
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIImage
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Camera QR scanner (AVFoundation) — the iOS counterpart of Android's QrScannerActivity. Presents
 * full-screen over the app and hands the first acceptable code to [onResult] (then closes).
 *
 * Focus: the back camera is the VIRTUAL triple/dual-wide device when the phone has one, so iOS itself
 * switches to the ultra-wide's macro when the code is close; on top, the start zoom follows Apple's
 * AVCamBarcode recipe (minimum focus distance vs. the distance a code of a given size fills the
 * frame), so a code held close enough to fill the preview is still in the lens's focus range.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosQrScanner {

    fun present(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val top = topViewController() ?: return onError("Нет окна для сканера")
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> show(top, onResult)
            AVAuthorizationStatusNotDetermined ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) show(top, onResult) else onError(CAMERA_DENIED)
                    }
                }
            else -> onError(CAMERA_DENIED)
        }
    }

    /** Decodes a QR from a picture (photo library); null when none is found. */
    fun decodeImage(image: UIImage): String? {
        val cg = image.CGImage ?: return null
        val detector = CIDetector.detectorOfType(
            CIDetectorTypeQRCode, null, mapOf<Any?, Any?>(CIDetectorAccuracy to CIDetectorAccuracyHigh)
        ) ?: return null
        return detector.featuresInImage(CIImage.imageWithCGImage(cg))
            .filterIsInstance<CIQRCodeFeature>()
            .firstNotNullOfOrNull { it.messageString?.trim()?.takeIf(::isAcceptableQr) }
    }

    /** Same filter as Android: share links, JSON configs and WireGuard/AmneziaWG INI. */
    fun isAcceptableQr(text: String): Boolean =
        text.isNotEmpty() && ("://" in text || text.startsWith("{") || text.startsWith("[") ||
            text.contains("[Interface]", ignoreCase = true))

    private fun show(top: UIViewController, onResult: (String) -> Unit) {
        val controller = ScannerController(onResult)
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        top.presentViewController(controller, animated = true, completion = null)
    }

    internal fun topViewController(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    private const val CAMERA_DENIED = "Нет доступа к камере — разрешите его в Настройках iOS"
}

// Top level: Kotlin/Native allows no companion fields on a subclass of an Objective-C class.
private const val TAG_CLOSE = 101
private const val TAG_TORCH = 102
private const val TAG_PHOTO = 103
private const val MIN_CODE_MM = 20.0
private const val FILL = 0.6
private const val MAX_ZOOM = 2.5

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class ScannerController(private val onResult: (String) -> Unit) :
    UIViewController(nibName = null, bundle = null) {

    private val session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer? = null
    private var device: AVCaptureDevice? = null
    private var delivered = false
    private val metadataDelegate = MetadataDelegate { deliver(it) }
    private val pickerDelegate = PickerDelegate(
        onImage = { image ->
            val text = IosQrScanner.decodeImage(image)
            if (text != null) deliver(text) else hint.text = "На фото не найден QR-код"
        },
    )
    private val hint = UILabel()

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        configureSession()
        preview = AVCaptureVideoPreviewLayer(session = session).also {
            it.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.addSublayer(it)
        }
        hint.text = "Наведите камеру на QR-код"
        hint.textColor = UIColor.whiteColor
        hint.textAlignment = NSTextAlignmentCenter
        hint.numberOfLines = 0
        view.addSubview(hint)
        view.addSubview(button("Закрыть") { dismiss() }.also { it.tag = TAG_CLOSE.toLong() })
        view.addSubview(button("Фонарик") { toggleTorch() }.also { it.tag = TAG_TORCH.toLong() })
        view.addSubview(button("Из фото") { pickPhoto() }.also { it.tag = TAG_PHOTO.toLong() })
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val (w, h) = view.bounds.useContents { size.width to size.height }
        preview?.frame = view.bounds
        val safeBottom = view.safeAreaInsets.useContents { bottom }
        val safeTop = view.safeAreaInsets.useContents { top }
        hint.setFrame(CGRectMake(24.0, safeTop + 24.0, w - 48.0, 60.0))
        val third = (w - 32.0) / 3
        listOf(TAG_CLOSE, TAG_PHOTO, TAG_TORCH).forEachIndexed { i, tag ->
            view.viewWithTag(tag.toLong())?.setFrame(CGRectMake(16.0 + i * third, h - safeBottom - 72.0, third, 56.0))
        }
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        setTorch(false)
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.stopRunning() }
    }

    private fun configureSession() {
        val camera = bestBackCamera() ?: run {
            hint.text = "Камера недоступна"
            return
        }
        device = camera
        session.beginConfiguration()
        if (session.canSetSessionPreset(AVCaptureSessionPreset1920x1080)) session.sessionPreset = AVCaptureSessionPreset1920x1080
        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, null)
        if (input != null && session.canAddInput(input)) session.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(metadataDelegate, dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
        session.commitConfiguration()
        tuneFocus(camera)
    }

    /** Virtual multi-camera first: iOS then switches lenses (incl. macro) by distance by itself. */
    private fun bestBackCamera(): AVCaptureDevice? =
        listOf(
            AVCaptureDeviceTypeBuiltInTripleCamera,
            AVCaptureDeviceTypeBuiltInDualWideCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
        ).firstNotNullOfOrNull { AVCaptureDevice.defaultDeviceWithDeviceType(it, AVMediaTypeVideo, AVCaptureDevicePositionBack) }
            ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)

    private fun tuneFocus(camera: AVCaptureDevice) {
        if (!camera.lockForConfiguration(null)) return
        try {
            if (camera.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) camera.focusMode = AVCaptureFocusModeContinuousAutoFocus
            if (camera.focusPointOfInterestSupported) camera.focusPointOfInterest = CGPointMake(0.5, 0.5)
            if (camera.isAutoFocusRangeRestrictionSupported()) camera.autoFocusRangeRestriction = AVCaptureAutoFocusRangeRestrictionNear
            if (camera.isSmoothAutoFocusSupported()) camera.smoothAutoFocusEnabled = false
            if (camera.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) camera.exposureMode = AVCaptureExposureModeContinuousAutoExposure
            if (camera.exposurePointOfInterestSupported) camera.exposurePointOfInterest = CGPointMake(0.5, 0.5)
            camera.videoZoomFactor = startZoom(camera)
        } finally {
            camera.unlockForConfiguration()
        }
    }

    /**
     * AVCamBarcode: the distance at which a [MIN_CODE_MM] code fills [FILL] of the frame's width; if that
     * is nearer than the lens can focus, zoom in so the user holds the phone farther away.
     */
    private fun startZoom(camera: AVCaptureDevice): Double {
        val minFocusMm = camera.minimumFocusDistance.toDouble()
        if (minFocusMm <= 0) return 1.0
        val fovRad = camera.activeFormat.videoFieldOfView.toDouble() * kotlin.math.PI / 180.0
        val subjectMm = (MIN_CODE_MM / FILL) / 2.0 / tan(fovRad / 2.0)
        if (subjectMm >= minFocusMm) return 1.0
        return min(max(minFocusMm / subjectMm, 1.0), min(camera.activeFormat.videoMaxZoomFactor.toDouble(), MAX_ZOOM))
    }

    private fun toggleTorch() {
        val on = device?.torchMode == AVCaptureTorchModeOn
        setTorch(!on)
    }

    private fun setTorch(on: Boolean) {
        val camera = device ?: return
        if (!camera.hasTorch || !camera.lockForConfiguration(null)) return
        camera.torchMode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        camera.unlockForConfiguration()
    }

    private fun pickPhoto() {
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        picker.delegate = pickerDelegate
        presentViewController(picker, animated = true, completion = null)
    }

    private fun deliver(text: String) {
        if (delivered || !IosQrScanner.isAcceptableQr(text)) return
        delivered = true
        dismissViewControllerAnimated(true) { onResult(text) }
    }

    private fun dismiss() = dismissViewControllerAnimated(true, completion = null)

    private fun button(title: String, onTap: () -> Unit): UIButton =
        UIButton.buttonWithType(UIButtonTypeSystem).apply {
            setTitle(title, forState = UIControlStateNormal)
            setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
            titleLabel?.font = UIFont.boldSystemFontOfSize(17.0)
            backgroundColor = UIColor.colorWithWhite(0.0, alpha = 0.45)
            layer.cornerRadius = 14.0
            addAction(UIAction.actionWithHandler { _ -> onTap() }, forControlEvents = UIControlEventTouchUpInside)
        }

}

@OptIn(ExperimentalForeignApi::class)
private class MetadataDelegate(private val onCode: (String) -> Unit) :
    NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue?.trim() }
            ?.let(onCode)
    }
}

private class PickerDelegate(private val onImage: (UIImage) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true) { image?.let(onImage) }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
    }
}
