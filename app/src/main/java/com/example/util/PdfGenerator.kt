package com.example.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.data.Transcription
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object PdfGenerator {
    
    private fun drawPageFrame(canvas: Canvas, pageWidth: Int, pageHeight: Int) {
        val pageFrame = RectF(25f, 25f, pageWidth - 25f, pageHeight - 25f)
        val paint = Paint()
        
        // Solid clean background
        paint.color = Color.rgb(255, 255, 255)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(pageFrame, 16f, 16f, paint)

        // Slate-200 border for ultra-clean look
        paint.color = Color.rgb(226, 232, 240)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(pageFrame, 16f, 16f, paint)
    }

    fun generatePdf(context: Context, transcription: Transcription): File? {
        val pdfDocument = PdfDocument()
        
        val pageWidth = 595
        val pageHeight = 842
        val margin = 50f
        val contentWidth = pageWidth - (margin * 2)
        
        var pagesCreated = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pagesCreated).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        
        // Draw first page frame
        drawPageFrame(canvas, pageWidth, pageHeight)
        
        val paint = Paint()
        
        val textPaint = TextPaint().apply {
            color = Color.rgb(51, 65, 85) // Slate-700
            textSize = 10.5f
            isAntiAlias = true
        }
        
        val titlePaint = TextPaint().apply {
            color = Color.rgb(15, 23, 42) // Slate-900 (Deep business text)
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }
        
        val subTitlePaint = TextPaint().apply {
            color = Color.rgb(100, 116, 139) // Slate-500
            textSize = 9f
            isAntiAlias = true
        }

        val headingPaint = TextPaint().apply {
            color = Color.rgb(79, 70, 229) // Indigo-600
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }

        var currentY = 55f

        // 1. Draw elegant badge pill
        val pillRect = RectF(50f, currentY, 160f, currentY + 18f)
        paint.color = Color.rgb(238, 242, 255) // Indigo-50 background
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(pillRect, 8f, 8f, paint)
        
        paint.color = Color.rgb(99, 102, 241) // Indigo border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        canvas.drawRoundRect(pillRect, 8f, 8f, paint)

        val pillTextPaint = TextPaint().apply {
            color = Color.rgb(79, 70, 229)
            textSize = 7.5f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("REPORTE DIGITAL", 68f, currentY + 12f, pillTextPaint)
        currentY += 32f

        // 2. Transcription Title with wrapping
        val titleLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.text.StaticLayout.Builder.obtain(transcription.title, 0, transcription.title.length, titlePaint, contentWidth.toInt()).build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(transcription.title, titlePaint, contentWidth.toInt(), android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
        }
        
        canvas.save()
        canvas.translate(50f, currentY)
        titleLayout.draw(canvas)
        canvas.restore()
        currentY += titleLayout.height + 15f

        // 3. Simple elegant details, NO APP NAME, NO "FECHA DE REGISTRO:" label, NO "FUENTE:" label.
        val dateString = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(transcription.timestamp))
        val langLabel = if (transcription.language.uppercase(Locale.getDefault()) == "ES") "ESPAÑOL" else "INGLÉS"
        canvas.drawText("REGISTRO: $dateString | IDIOMA: $langLabel", 50f, currentY, subTitlePaint)
        currentY += 12f

        paint.color = Color.rgb(226, 232, 240)
        paint.strokeWidth = 1f
        canvas.drawLine(50f, currentY + 4f, margin + contentWidth, currentY + 4f, paint)
        currentY += 24f

        // 4. Content Paragraphs Text
        val textLines = transcription.text.split("\n")
        for (paragraph in textLines) {
            val trimmedParagraph = paragraph.trim()
            if (trimmedParagraph.isEmpty()) {
                currentY += 8f
                continue
            }
            
            val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(trimmedParagraph, 0, trimmedParagraph.length, textPaint, contentWidth.toInt()).build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(trimmedParagraph, textPaint, contentWidth.toInt(), android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0.0f, false)
            }
            
            // If paragraph overflows the page frame margin
            if (currentY + staticLayout.height > pageHeight - margin) {
                pdfDocument.finishPage(page)
                pagesCreated++
                page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pagesCreated).create())
                canvas = page.canvas
                drawPageFrame(canvas, pageWidth, pageHeight)
                currentY = 55f
            }
            
            canvas.save()
            canvas.translate(50f, currentY)
            staticLayout.draw(canvas)
            canvas.restore()
            currentY += staticLayout.height + 12f
        }

        // 5. Drawing Summary Box inside colored card
        val summaryText = transcription.summary
        if (!summaryText.isNullOrBlank()) {
            val staticSummaryLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(summaryText, 0, summaryText.length, textPaint, (contentWidth - 24f).toInt()).build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(summaryText, textPaint, (contentWidth - 24f).toInt(), android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0.0f, false)
            }

            val boxHeight = staticSummaryLayout.height + 45f

            // Check overflow
            if (currentY + boxHeight > pageHeight - margin) {
                pdfDocument.finishPage(page)
                pagesCreated++
                page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pagesCreated).create())
                canvas = page.canvas
                drawPageFrame(canvas, pageWidth, pageHeight)
                currentY = 55f
            }

            currentY += 10f
            // Draw violet background box
            val summaryBox = RectF(50f, currentY, pageWidth - 50f, currentY + boxHeight)
            paint.color = Color.rgb(245, 243, 255) // light violet background
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(summaryBox, 12f, 12f, paint)

            paint.color = Color.rgb(233, 213, 255) // violet border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(summaryBox, 12f, 12f, paint)

            // Indigo left tag bar
            paint.color = Color.rgb(124, 58, 237)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(50f, currentY, 56f, currentY + boxHeight), 4f, 4f, paint)

            // Header title inside card
            canvas.drawText("SÍNTESIS ANALÍTICA", 68f, currentY + 22f, headingPaint.apply { color = Color.rgb(109, 40, 217) })

            // Draw Summary Layout inside box
            canvas.save()
            canvas.translate(68f, currentY + 34f)
            staticSummaryLayout.draw(canvas)
            canvas.restore()

            currentY += boxHeight + 15f
        }

        // 6. Action Items inside colored card
        val actionsVal = transcription.actionItems
        if (!actionsVal.isNullOrBlank()) {
            val staticActionsLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(actionsVal, 0, actionsVal.length, textPaint, (contentWidth - 24f).toInt()).build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(actionsVal, textPaint, (contentWidth - 24f).toInt(), android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0.0f, false)
            }

            val boxHeight = staticActionsLayout.height + 45f

            // Check overflow
            if (currentY + boxHeight > pageHeight - margin) {
                pdfDocument.finishPage(page)
                pagesCreated++
                page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pagesCreated).create())
                canvas = page.canvas
                drawPageFrame(canvas, pageWidth, pageHeight)
                currentY = 55f
            }

            currentY += 10f
            // Draw green emerald background box
            val actionsBox = RectF(50f, currentY, pageWidth - 50f, currentY + boxHeight)
            paint.color = Color.rgb(240, 253, 250) // light emerald background
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(actionsBox, 12f, 12f, paint)

            paint.color = Color.rgb(204, 251, 241) // emerald border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(actionsBox, 12f, 12f, paint)

            // Emerald left bar tag
            paint.color = Color.rgb(13, 148, 136)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(50f, currentY, 56f, currentY + boxHeight), 4f, 4f, paint)

            // Header title inside card
            canvas.drawText("PUNTOS RELEVANTES Y DECISIONES", 68f, currentY + 22f, headingPaint.apply { color = Color.rgb(15, 118, 110) })

            // Draw Actions Layout inside box
            canvas.save()
            canvas.translate(68f, currentY + 34f)
            staticActionsLayout.draw(canvas)
            canvas.restore()

            currentY += boxHeight + 15f
        }
        
        pdfDocument.finishPage(page)

        val pureTitle = transcription.title.replace("[^a-zA-Z0-9]".toRegex(), "_")
        val fileName = "Doc_${pureTitle}_${System.currentTimeMillis()}.pdf"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val pdfFile = java.io.File(storageDir, fileName)
        
        return try {
            val outputStream = FileOutputStream(pdfFile)
            pdfDocument.writeTo(outputStream)
            outputStream.flush()
            outputStream.close()
            pdfDocument.close()
            pdfFile
        } catch (e: Exception) {
            Log.e("PdfGenerator", "Error generating PDF: ${e.localizedMessage}")
            pdfDocument.close()
            null
        }
    }
}
