/*
 * Licensed to Typesafe under one or more contributor license agreements.
 * See the AUTHORS file distributed with this work for
 * additional information regarding copyright ownership.
 * This file is licensed to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.squbs.httpclient.pipeline.impl

import akka.actor.ActorContext
import org.squbs.pipeline.ProxyResponse._
import org.squbs.pipeline.{Handler, RequestContext}
import spray.http.HttpHeader

import scala.concurrent.{ExecutionContext, Future}

class ResponseAddHeaderHandler(httpHeader: HttpHeader) extends Handler {
	override def process(reqCtx: RequestContext)(implicit executor: ExecutionContext, context: ActorContext): Future[RequestContext] = {
		Future {
			reqCtx.copy(response = reqCtx.response + httpHeader)
		}
  }
}

class ResponseRemoveHeaderHandler(httpHeader: HttpHeader) extends Handler {
	override def process(reqCtx: RequestContext)(implicit executor: ExecutionContext, context: ActorContext): Future[RequestContext] = {
    Future {
			reqCtx.copy(response = reqCtx.response - httpHeader)
		}
  }
}

class ResponseUpdateHeaderHandler(httpHeader: HttpHeader) extends Handler {
	override def process(reqCtx: RequestContext)(implicit executor: ExecutionContext, context: ActorContext): Future[RequestContext] = {
    Future {
			reqCtx.copy(response = reqCtx.response - httpHeader + httpHeader)
		}
  }
}