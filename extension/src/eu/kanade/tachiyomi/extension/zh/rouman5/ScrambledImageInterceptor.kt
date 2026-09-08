package eu.kanade.tachiyomi.extension.zh.rouman5

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.security.MessageDigest

class ScrambledImageInterceptor : Interceptor {

    override fun intercept(
        chain: Interceptor.Chain,
    ): Response {

        val request =
            chain.request()

        val response =
            chain.proceed(request)

        val url =
            request.url

        /*
         * 只有 /sr:1/ 图片需要还原。
         */
        if (
            "sr:1" !in url.pathSegments
        ) {
            return response
        }

        val responseBody =
            response.body
                ?: return response

        val image =
            responseBody.use {
                BitmapFactory.decodeStream(
                    it.byteStream(),
                )
            }

        /*
         * 如果服务器返回的并不是有效图片，
         * 不进行处理。
         */
        if (
            image == null
        ) {
            return response
        }

        val width =
            image.width

        val height =
            image.height

        if (
            width <= 0 ||
            height <= 0
        ) {
            image.recycle()
            return response
        }

        val result =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
            )

        val canvas =
            Canvas(result)

        /*
         * 肉漫屋官方算法：
         *
         * 文件名
         *     ↓
         * Base64 decode
         *     ↓
         * MD5
         *     ↓
         * 最后一个 byte
         *     ↓
         * % 10 + 5
         *
         * 得到图片分块数量。
         */
        val blocks =
            try {

                url.pathSegments
                    .last()
                    .substringBeforeLast(
                        '.',
                    )
                    .let {
                        Base64.decode(
                            it,
                            Base64.DEFAULT,
                        )
                    }
                    .let {
                        MessageDigest
                            .getInstance("MD5")
                            .digest(
                                it,
                            )
                    }
                    .let {
                        it.last()
                            .toPositiveInt() % 10 + 5
                    }

            } catch (
                _: Exception,
            ) {

                image.recycle()
                result.recycle()

                return response
            }

        val blockHeight =
            height / blocks

        /*
         * scrambled 图片的最后一个区块
         * 位于原始图片顶部。
         */
        var sourceY =
            blockHeight *
                (blocks - 1)

        var destinationY =
            0

        for (
            index in 0 until blocks
        ) {

            /*
             * remainder 在底部 scrambled block。
             */
            val currentHeight =
                if (
                    index == 0
                ) {
                    height - sourceY
                } else {
                    blockHeight
                }

            val sourceRect =
                Rect(
                    0,
                    sourceY,
                    width,
                    sourceY + currentHeight,
                )

            val destinationRect =
                Rect(
                    0,
                    destinationY,
                    width,
                    destinationY + currentHeight,
                )

            canvas.drawBitmap(
                image,
                sourceRect,
                destinationRect,
                null,
            )

            sourceY -= blockHeight
            destinationY += currentHeight
        }

        val newBody =
            Buffer().run {

                result.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    outputStream(),
                )

                asResponseBody(
                    "image/jpeg".toMediaType(),
                )
            }

        image.recycle()
        result.recycle()

        return response
            .newBuilder()
            .body(
                newBody,
            )
            .build()
    }

    private fun Byte.toPositiveInt(): Int =
        toInt() and 0xFF
}
