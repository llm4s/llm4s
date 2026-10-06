package org.llm4s.speech.processing

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId
import org.llm4s.error.ProcessingError
import org.llm4s.speech.AudioMeta

/** Validation helpers for raw audio data and its associated metadata. */
object AudioValidations {

  /**
   * Bytes per PCM frame (`numChannels * bitDepth / 8`), or `None` when the metadata describes no whole-byte PCM frame:
   * a non-positive channel count, or a bit depth below 8 - including the `bitDepth = 0` that compressed audio such as
   * [[org.llm4s.speech.AudioFormat.Mp3]] carries.
   */
  private[processing] def pcmFrameSize(meta: AudioMeta): Option[Int] = {
    val frameSize = meta.numChannels * (meta.bitDepth / 8)
    Option.when(meta.numChannels > 0 && frameSize > 0)(frameSize)
  }

  private[processing] def validateFrameSize(
    input: (Array[Byte], AudioMeta)
  ): ValidatedNel[ProcessingError, (Array[Byte], AudioMeta)] = {
    val (bytes, meta) = input
    pcmFrameSize(meta) match {
      case None =>
        ProcessingError
          .audioValidation(
            s"Audio metadata has no PCM frame size (${meta.numChannels} channels, ${meta.bitDepth}-bit); " +
              "compressed audio such as MP3 cannot be validated as PCM"
          )
          .invalidNel
      case Some(frameSize) if bytes.length % frameSize != 0 =>
        ProcessingError
          .audioValidation(
            s"Audio data length (${bytes.length}) is not a multiple of frame size (${frameSize})"
          )
          .invalidNel
      case Some(_) =>
        input.validNel
    }
  }

  private[processing] def validateInputNotEmpty(
    input: (Array[Byte], AudioMeta)
  ): ValidatedNel[ProcessingError, (Array[Byte], AudioMeta)] = {
    val (bytes, _) = input
    if (bytes.isEmpty)
      ProcessingError.audioValidation("Audio data is empty").invalidNel
    else
      input.validNel
  }

  private[processing] def validateSampleRate(meta: AudioMeta): ValidatedNel[ProcessingError, Unit] =
    if (meta.sampleRate <= 0)
      ProcessingError.audioValidation("Sample rate must be positive").invalidNel
    else
      ().validNel

  private[processing] def validateNumChannels(meta: AudioMeta): ValidatedNel[ProcessingError, Unit] =
    if (meta.numChannels <= 0)
      ProcessingError.audioValidation("Number of channels must be positive").invalidNel
    else
      ().validNel

  private[processing] def validateBitDepth(meta: AudioMeta): ValidatedNel[ProcessingError, Unit] =
    if (meta.bitDepth != 16)
      ProcessingError.audioValidation("Only 16-bit audio is supported").invalidNel
    else
      ().validNel

  private[processing] def validateSampleRateIsNotTooHigh(meta: AudioMeta): ValidatedNel[ProcessingError, Unit] =
    if (meta.sampleRate > 48000)
      ProcessingError.audioValidation("Sample rate too high for STT").invalidNel
    else
      ().validNel

}
