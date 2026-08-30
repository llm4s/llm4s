package org.llm4s.speech.processing

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId
import org.llm4s.error.ProcessingError
import org.llm4s.speech.AudioMeta

/** Validation helpers for raw audio data and its associated metadata. */
object AudioValidations {
  private[processing] def validateFrameSize(
    input: (Array[Byte], AudioMeta)
  ): ValidatedNel[ProcessingError, (Array[Byte], AudioMeta)] = {
    val (bytes, meta)  = input
    val expectedLength = meta.numChannels * (meta.bitDepth / 8)

    if (bytes.length % expectedLength != 0)
      ProcessingError
        .audioValidation(
          s"Audio data length (${bytes.length}) is not a multiple of frame size (${expectedLength})"
        )
        .invalidNel
    else
      input.validNel
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
