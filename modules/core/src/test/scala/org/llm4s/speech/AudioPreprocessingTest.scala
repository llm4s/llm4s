package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.processing.AudioPreprocessing

class AudioPreprocessingTest extends AnyFunSuite {

  test("toMono keeps mono identical") {
    val bytes = Array[Byte](0, 0, 1, 0, -1, -1, 0, 0)
    val meta  = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    val out   = AudioPreprocessing.toMono(bytes, meta)
    assert(out.exists(_._1.sameElements(bytes)))
    assert(out.exists(_._2.numChannels == 1))
  }
}
