package io.iohk.ethereum.jsonrpc

import io.iohk.ethereum.metrics.MetricsContainer

case object JsonRpcControllerMetrics extends MetricsContainer {

  /**
    * Counts attempts to call non-existing methods.
    */
  final val NotFoundMethodsCounter = metrics.counter("json.rpc.notfound.calls.counter")

  final val MethodsTimer = metrics.timer("json.rpc.methods.timer")
  final val MethodsSuccessCounter = metrics.counter("json.rpc.methods.success.counter")
  final val MethodsExceptionCounter = metrics.counter("json.rpc.methods.exception.counter")
  final val MethodsErrorCounter = metrics.counter("json.rpc.methods.error.counter")
}