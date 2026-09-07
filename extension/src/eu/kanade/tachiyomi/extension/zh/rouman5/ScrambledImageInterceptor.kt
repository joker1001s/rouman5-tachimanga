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

/**
 * 肉漫屋漫画图片还原拦截器。
 *
 * 肉漫屋的漫画图片 URL 通常包含：
 *
 *     /sr:1/
 *
 * 图片本身并不是按照正常顺序存储，
 * 而是被分成多个横向区块后倒序排列。
 *
 * 图片文件名经过 Base64 解码后，
 * 使用 MD5 最后一个字节计算区块数量。
 *
 * 算法来源于肉漫屋当前网页端的图片处理逻辑。
 */
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
         * 只有 /sr:1/ 的图片需要进行还原。
         *
         * 广告、封面、头像、logo 等普通图片
         * 不进行处理。
         */
        if ("sr:1" !in url.pathSegments) {
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
                ?: return response

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

        /*
         * 获取图片文件名。
         *
         * 例如：
         *
         *     /sr:1/xxxxxxxxxxxx.jpg
         *
         * 只取：
         *
         *     xxxxxxxxxxxx
         */
        val encodedName =
            url.pathSegments
                .lastOrNull()
                ?.substringBeforeLast(
                    ".",
                )

        if (
            encodedName.isNullOrBlank()
        ) {
            image.recycle()
            return response
        }

        val blocks =
            try {

                /*
                 * Base64 解码文件名
                 */
                val decoded =
                    Base64.decode(
                        encodedName,
                        Base64.DEFAULT,
                    )

                /*
                 * MD5
                 */
                val digest =
                    MessageDigest
                        .getInstance("MD5")
                        .digest(
                            decoded,
                        )

                /*
                 * 肉漫屋当前算法：
                 *
                 * lastByte % 10 + 5
                 *
                 * 最终区块数量：
                 *
                 * 5 ~ 14
                 */
                digest
                    .last()
                    .toPositiveInt() % 10 + 5

            } catch (
                _: Exception,
            ) {

                image.recycle()
                return response
            }

        if (blocks <= 1) {
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
         * 图片被按照横向区块分割。
         *
         * 注意：
         *
         * 如果 height 无法被 blocks 整除，
         * 多出来的 remainder 位于底部区块。
         */
        val blockHeight =
            height / blocks

        /*
         * 原图从最后一个区块开始。
         */
        var sourceY =
            blockHeight * (blocks - 1)

        /*
         * 目标图片从顶部开始。
         */
        var destinationY =
            0

        for (
            index in 0 until blocks
        ) {

            /*
             * 第一个复制出来的区块，
             * 实际对应原图最底部区块。
             *
             * 底部区块包含 remainder。
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

        /*
         * Tachimanga 最终拿到正常 JPG。
         */
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
            .body(newBody)
            .build()
    }

    private fun Byte.toPositiveInt(): Int {
        return toInt() and 0xFF
    }
}
