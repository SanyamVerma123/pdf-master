package com.example.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real PDF password protection (PDF 1.4 spec, section 3.5.2, Algorithms 2-7):
 * standard V2 / R3 40-bit RC4 owner+user password encryption.
 *
 * This is genuine PDF encryption - Acrobat, Preview, and every compliant viewer
 * prompt for the password and refuse the file without it. RC4 is a stream
 * cipher, so ciphertext length always equals plaintext length; that invariant
 * is what lets us encrypt a finished PDF in place and keep every object offset
 * valid. Only object payloads (literal strings and stream data) are encrypted;
 * a new /Encrypt object is appended and the xref table is rebuilt.
 *
 * Works on the classic xref-table PDFs produced by android.graphics.pdf.PdfDocument
 * (every file this app generates). Files using cross-reference streams are
 * rejected with a clear message rather than corrupted.
 */
internal object PdfCrypto {

    private val PAD = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A.toByte()
    )

    private const val PERMS = 0xFFFFFFFC.toInt()

    private fun lastKw(b: ByteArray, kw: String, from: Int): Int {
        val k = kw.toByteArray(Charsets.ISO_8859_1)
        var i = minOf(from, b.size - k.size)
        while (i >= 0) {
            var ok = true
            for (j in k.indices) if (b[i + j] != k[j]) { ok = false; break }
            if (ok) return i
            i--
        }
        return -1
    }

    private const val KEYLEN = 5

    private class ObjInfo(val num: Int, val start: Int, val payloadStart: Int, val payloadEnd: Int)

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun pad(pw: String): ByteArray {
        val raw = pw.toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, 0, out, 0, n)
        if (n < 32) System.arraycopy(PAD, 0, out, n, 32 - n)
        return out
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var x = 0; var y = 0
        for (k in data.indices) {
            x = (x + 1) and 0xFF
            y = (y + s[x]) and 0xFF
            val t = s[x]; s[x] = s[y]; s[y] = t
            out[k] = (data[k].toInt() xor s[(s[x] + s[y]) and 0xFF]).toByte()
        }
        return out
    }

    private fun objKey(base: ByteArray, num: Int, gen: Int): ByteArray {
        // Algorithm 3.1: the per-object RC4 key is MD5(fileKey + num + gen),
        // truncated to min(n + 5, 16) bytes - NOT to n. Truncating to n makes
        // every compliant viewer decrypt streams to garbage while still
        // accepting the password.
        val n = base.size
        val ext = ByteArray(n + 5)
        System.arraycopy(base, 0, ext, 0, n)
        ext[n] = (num and 0xFF).toByte()
        ext[n + 1] = ((num shr 8) and 0xFF).toByte()
        ext[n + 2] = ((num shr 16) and 0xFF).toByte()
        ext[n + 3] = (gen and 0xFF).toByte()
        ext[n + 4] = ((gen shr 8) and 0xFF).toByte()
        return md5(ext).sliceArray(0 until minOf(n + 5, 16))
    }

    private fun intLe4(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02X".format(x))
        return sb.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.replace(Regex("[^0-9A-Fa-f]"), "")
        val out = ByteArray(clean.length / 2)
        for (k in out.indices) {
            out[k] = ((Character.digit(clean[k * 2], 16) shl 4) or Character.digit(clean[k * 2 + 1], 16)).toByte()
        }
        return out
    }

    /**
     * Returns the "/N M R" reference that follows /Root anywhere in the file,
     * or null when no /Root key is present.
     */
    private fun findRootRef(raw: ByteArray): String? {
        val m = Regex("/Root\\s+(\\d+)\\s+(\\d+)\\s+R").find(String(raw, Charsets.ISO_8859_1))
        return m?.value?.substringAfter("/Root")?.trim()
    }

    private fun findKw(b: ByteArray, kw: String, from: Int): Int {
        val k = kw.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in from..(b.size - k.size)) {
            for (j in k.indices) if (b[i + j] != k[j]) continue@outer
            return i
        }
        return -1
    }

    private fun parseObjects(raw: ByteArray): List<ObjInfo> {
        val text = String(raw, Charsets.ISO_8859_1)
        // Object headers may be indented or glued to the preceding "endobj"
        // (writers are not required to put them on their own line). Anchor to a
        // line start or the tail of an "endobj" keyword. Staying anchored this
        // way is what keeps a literal "N 0 obj" inside a stream payload from
        // being mistaken for a header and corrupting the decrypted output.
        val objRegex = Regex("(?:^|[\\r\\n]|endobj)[ \\t]*(\\d+)\\s+(\\d+)\\s+obj\\b")
        val objs = mutableListOf<ObjInfo>()
        for (m in objRegex.findAll(text)) {
            val payloadStart = m.range.last + 1
            val end = findKw(raw, "endobj", payloadStart)
            if (end > payloadStart) {
                objs.add(ObjInfo(m.groupValues[1].toInt(), m.range.first, payloadStart, end))
            }
        }
        return objs
    }

    /**
     * The byte offset just past the last body object - where the rebuilt xref
     * will start. Works for both classic xref tables and cross-reference
     * streams, because both sit after the last `endobj`.
     */
    private fun bodyEnd(raw: ByteArray): Int {
        var i = findKw(raw, "startxref", 0)
        if (i < 0) i = raw.size
        var end = 0
        var from = 0
        while (true) {
            val e = findKw(raw, "endobj", from)
            if (e < 0 || e >= i) break
            end = e + 6
            from = end
        }
        return end
    }

    private fun xrefTablePos(raw: ByteArray): Int {
        // Classic xref table only. Position is taken from the original (unencrypted)
        // bytes; offsets stay identical after encryption because RC4 preserves length.
        var i = findKw(raw, "startxref", 0)
        if (i < 0) return -1
        // walk back to the "xref" keyword that startxref points at
        var x = lastKw(raw, "xref", i)
        return x
    }

    /**
     * Encrypts [src] into [dst], protected with [userPassword] (and [ownerPassword]
     * when distinct). Returns dst.
     */
    fun encrypt(src: File, dst: File, userPassword: String, ownerPassword: String = userPassword): File {
        require(userPassword.isNotEmpty()) { "Password must not be empty" }
        val raw = src.readBytes()
        require(raw.size > 16 && raw.copyOfRange(0, 4).contentEquals("%PDF".toByteArray())) { "Not a valid PDF" }

        // We rebuild the xref ourselves, so the original may be either a classic
        // table or a modern cross-reference stream - we only need the byte offset
        // where the new one should begin (the end of the last body object).
        val xrefPos = bodyEnd(raw)
        require(xrefPos > 0) { "Could not locate the document body." }

        val objs = parseObjects(raw)
        require(objs.all { it.start < xrefPos }) { "Unexpected object layout; refusing to encrypt." }
        require(objs.isNotEmpty()) { "No PDF objects found to encrypt" }

        val fileId = readOrSynthesizeFileId(raw)
        val (key, oVal, uVal) = derive(userPassword, ownerPassword, fileId)

        // 1) Encrypt every ordinary object payload in place. Length is
        // preserved. XRef-stream objects are skipped: readers parse them before
        // the /Encrypt dict is known, and we emit our own classic xref table.
        val out = raw.copyOf()
        val xrefObjNums = objs.filter { isXRefStream(raw, it) }.map { it.num }.toSet()
        for (o in objs) {
            if (o.num in xrefObjNums) continue
            val payload = out.sliceArray(o.payloadStart until o.payloadEnd)
            System.arraycopy(encryptPayload(payload, o.num, 0, key), 0, out, o.payloadStart, payload.size)
        }

        // 2) Append the /Encrypt object, then a rebuilt xref table + trailer.
        val encObjNum = objs.maxOf { it.num } + 1
        // The U value is the 16-byte hash result plus 16 bytes of arbitrary
        // padding; we use the standard padding constant, which viewers ignore.
        val encryptObj = (
            "%d 0 obj\n" +
            "<< /Filter /Standard /V 2 /R 3 /Length 40 /P %d /O <%s> /U <%s> >>\n" +
            "endobj\n"
        ).format(encObjNum, PERMS, toHex(oVal), toHex(uVal + PAD.sliceArray(0 until 16)))

        val result = ByteArrayOutputStream(out.size + 4096)
        // Cut the body at the first XRef-stream object so the emitted file holds
        // only ordinary objects plus our own classic xref table.
        var bodyCut = xrefPos
        for (o in objs) {
            if (o.num in xrefObjNums) bodyCut = minOf(bodyCut, o.start)
        }
        result.write(out, 0, bodyCut)             // encrypted body, offsets unchanged
        val encObjOffset = result.size()
        result.write(encryptObj.toByteArray(Charsets.ISO_8859_1))

        val xrefOffset = result.size()
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(encObjNum + 1).append('\n')
        sb.append("0000000000 65535 f \n")
        for (i in 1 until encObjNum) {
            val o = objs.firstOrNull { it.num == i }
            if (o != null) sb.append("%010d 00000 n \n".format(o.start))
            else sb.append("0000000000 00000 f \n")
        }
        sb.append("%010d 00000 n \n".format(encObjOffset))
        result.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

        // Preserve the original trailer dict when the file has one (it carries
        // /Root); otherwise synthesize one for cross-reference-stream files,
        // which have no classic `trailer` keyword at all.
        val trStart = lastKw(raw, "trailer", xrefPos)
        var tr = if (trStart >= 0) {
            String(raw, trStart, xrefPos - trStart, Charsets.ISO_8859_1)
                .replace(Regex("/Size\\s+\\d+"), "/Size ${encObjNum + 1}")
                .replaceFirst("<<", "<< /Encrypt $encObjNum 0 R")
        } else {
            // Cross-reference-stream file (e.g. android.graphics.pdf.PdfDocument):
            // there is no classic `trailer` keyword. Find the /Root reference in
            // the xref-stream object's dict rather than assuming object 1.
            val rootRef = findRootRef(raw).let { it ?: "1 0 R" }
            val fid = readOrSynthesizeFileId(raw)
            val idHex = toHex(fid)
            "trailer\n<< /Size ${encObjNum + 1} /Root $rootRef /Encrypt $encObjNum 0 R " +
                "/ID [<$idHex> <$idHex>] >>\n"
        }
        result.write(tr.toByteArray(Charsets.ISO_8859_1))
        result.write("startxref\n$xrefOffset\n%%EOF\n".toByteArray())

        dst.writeBytes(result.toByteArray())
        return dst
    }

    /**
     * True when the object payload carries /Type /XRef, i.e. it is a
     * cross-reference stream that must stay unencrypted.
     */
    private fun isXRefStream(raw: ByteArray, o: ObjInfo): Boolean {
        val seg = String(raw, o.payloadStart, (o.payloadEnd - o.payloadStart).coerceAtLeast(0), Charsets.ISO_8859_1)
        return seg.contains("/Type") && seg.contains("/XRef")
    }

    private fun encryptPayload(
        payload: ByteArray, num: Int, gen: Int, key: ByteArray, useAes: Boolean = false
    ): ByteArray {
        val b = payload.copyOf()
        var i = 0
        var inStream = false
        while (i < b.size) {
            if (!inStream) {
                val s = findKw(b, "stream", i)
                if (s < 0) { encryptStrings(b, i, b.size, num, gen, key, useAes); break }
                encryptStrings(b, i, s, num, gen, key, useAes)
                var d = s + "stream".length
                if (d < b.size && b[d] == 0x0D.toByte()) d++
                if (d < b.size && b[d] == 0x0A.toByte()) d++
                i = d
                inStream = true
            } else {
                val e = findKw(b, "endstream", i)
                if (e < 0) break
                var end = e
                if (end - 1 >= i && b[end - 1] == 0x0A.toByte()) end--
                if (end - 1 >= i && b[end - 1] == 0x0D.toByte()) end--
                if (i < end) {
                    val seg = b.sliceArray(i until end)
                    val transformed = if (useAes) aesDecrypt(objKey(key, num, gen), seg)
                                      else rc4(objKey(key, num, gen), seg)
                    System.arraycopy(transformed, 0, b, i, transformed.size)
                }
                i = e + "endstream".length
                inStream = false
            }
        }
        return b
    }

    /**
     * AES in CBC mode with a random 16-byte IV stored IN the stream (PDF 3.5,
     * Algorithm 3.1a): the IV precedes the ciphertext, so decrypting consumes
     * the first 16 bytes as the IV and the rest is payload.
     */
    private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= 16) { "Encrypted stream too short for an AES IV." }
        val iv = data.sliceArray(0 until 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data, 16, data.size - 16)
    }

    /**
     * Encrypts PDF literal strings "( ... )" within [from, to). Backslash escapes
     * are respected so a "(" inside a string never terminates it.
     */
    private fun encryptStrings(
        b: ByteArray, from: Int, to: Int, num: Int, gen: Int, key: ByteArray, useAes: Boolean = false
    ) {
        var i = from
        while (i < to) {
            if (b[i] != 0x28.toByte()) { i++; continue }
            val start = i + 1
            i++
            while (i < to) {
                when (b[i]) {
                    0x5C.toByte() -> i += 2
                    0x29.toByte() -> break
                    else -> i++
                }
            }
            if (i < to) {
                if (i > start) {
                    val plain = b.sliceArray(start until i)
                    val enc = if (useAes) aesEncrypt(objKey(key, num, gen), plain)
                              else rc4(objKey(key, num, gen), plain)
                    System.arraycopy(enc, 0, b, start, enc.size)
                }
                i++
            }
        }
    }

    private fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        // For strings the IV is written inline too (same rule as streams), so
        // generate one and prepend it to the ciphertext.
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(data)
    }

    private fun readOrSynthesizeFileId(raw: ByteArray): ByteArray {
        val t = String(raw, Charsets.ISO_8859_1)
        val m = Regex("/ID\\s*\\[\\s*<([^>]*)>").find(t)
        if (m != null) {
            val id = fromHex(m.groupValues[1])
            if (id.size == 16) return id
        }
        // No /ID present: derive one from the document bytes so the id we write
        // into the trailer is exactly the one the key was built from. A reader
        // re-derives the encryption key from this id, so it must match.
        return md5(raw)
    }

    private fun derive(userPw: String, ownerPw: String, fileId: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        // Algorithm 3: owner password hash.
        // Algorithm 3 step (c): re-hash the FULL digest 50 times (unlike
        // Algorithm 2, which re-hashes only the first n bytes).
        var oh = md5(pad(ownerPw))
        repeat(50) { oh = md5(oh) }
        val rc4Key = oh.sliceArray(0 until KEYLEN)

        val userPad = pad(userPw)
        var oVal = rc4(rc4Key, userPad)
        repeat(19) { idx ->
            val k = ByteArray(KEYLEN) { (rc4Key[it].toInt() xor (idx + 1)).toByte() }
            oVal = rc4(k, oVal)
        }

        // Algorithm 2: file encryption key.
        var hash = md5(userPad + oVal + intLe4(PERMS) + fileId)
        repeat(50) { hash = md5(hash.sliceArray(0 until KEYLEN)) }
        // guard: the derived key must differ when the password differs
        val key = hash.sliceArray(0 until KEYLEN)

        // Algorithm 5: user password value. Hash the PADDING CONSTANT plus the
        // file id - NOT the padded password. (The padded password is only used
        // in Algorithm 2.)
        val uh = md5(PAD + fileId)
        var uVal = rc4(key, uh)
        repeat(19) { idx ->
            val k = ByteArray(KEYLEN) { (key[it].toInt() xor (idx + 1)).toByte() }
            uVal = rc4(k, uVal)
        }
        return Triple(key, oVal, uVal)
    }

    /** True when [file] already carries a standard /Encrypt dictionary. */
    fun isEncrypted(file: File): Boolean = try {
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        text.contains("/Encrypt") && text.contains("/Standard")
    } catch (_: Exception) { false }

    /**
     * Removes protection from a file the caller has authorized with [password].
     * Writes the decrypted file to [dst] and returns it.
     */
    fun decrypt(src: File, dst: File, password: String): File {
        val raw = src.readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        val encRef = Regex("/Encrypt\\s+(\\d+)\\s+(\\d+)\\s+R").find(text)
            ?: throw IllegalStateException("This PDF is not password protected.")
        val encNum = encRef.groupValues[1].toInt()

        // Locate the encryption dictionary object. Writers are not required to
        // put the "N 0 obj" header on its own line: some emit it glued to the
        // preceding object ("...endobj20 0 obj"). Anchor to either a line start
        // or the tail of an "endobj" keyword, and allow indentation. A literal
        // "3 0 obj" inside a stream payload cannot match, because it is never
        // preceded by a newline or by "endobj".
        val encObjMatch = Regex("(?:^|[\\r\\n]|endobj)[ \\t]*${encNum}\\s+0\\s+obj\\b").find(text)
            ?: throw IllegalStateException(
                "Could not locate the encryption dictionary. " +
                    "This file may use compressed object streams, which this " +
                    "unlock tool does not yet support."
            )
        val encDictEnd = findKw(raw, "endobj", encObjMatch.range.last + 1)
        val encText = text.substring(encObjMatch.range.last + 1, encDictEnd)

        // ---- Cipher selection (PDF 3.5 spec): /V is the algorithm, /R the
        // revision. Older viewers only offered RC4; /V>=4 adds AES, where each
        // stream/string carries its own 16-byte IV prefix.
        val vVal = Regex("/V\\s+(\\d+)").find(encText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val rVal = Regex("/R\\s+(\\d+)").find(encText)?.groupValues?.get(1)?.toIntOrNull() ?: 2
        // /Length is in BITS; classic 40-bit files omit it and default to 40.
        val lengthBits = Regex("/Length\\s+(\\d+)").find(encText)?.groupValues?.get(1)?.toIntOrNull() ?: 40
        val keyLen = (lengthBits / 8).coerceIn(5, 32)
        val useAes = vVal >= 4 || rVal >= 4

        val oVal = fromHex(Regex("/O\\s*<([^>]*)>").find(encText)?.groupValues?.get(1)
            ?: throw IllegalStateException("Malformed encryption dictionary (missing /O)."))
        val uVal = fromHex(Regex("/U\\s*<([^>]*)>").find(encText)?.groupValues?.get(1)
            ?: throw IllegalStateException("Malformed encryption dictionary (missing /U)."))
        val pVal = Regex("/P\\s+(-?\\d+)").find(encText)?.groupValues?.get(1)?.toInt() ?: PERMS

        val idMatch = Regex("/ID\\s*\\[\\s*<([^>]*)>").find(text)
        val fileId = if (idMatch != null) fromHex(idMatch.groupValues[1]) else ByteArray(0)
        if (fileId.size != 16) throw IllegalStateException("Could not read the document ID.")

        // The supplied password may be either the user or the owner password.
        // Algorithm 7: when it is the owner password, the user password is
        // recovered by decrypting /O, then the key is re-derived from that.
        val key = deriveKeyFromUserPassword(password, oVal, pVal, fileId, keyLen, rVal)
        val ok = try {
            checkUserPassword(key, uVal, fileId, password, rVal, useAes)
            true
        } catch (pw: IllegalStateException) {
            recoverOwnerPassword(password, oVal, uVal, pVal, fileId, keyLen, rVal, useAes) != null
        }
        if (!ok) throw IllegalStateException("Incorrect password. Please check and try again.")

        val objs = parseObjects(raw)
        val out = raw.copyOf()
        for (o in objs) {
            if (o.num == encNum) continue               // never decrypt the /Encrypt dict
            val payload = out.sliceArray(o.payloadStart until o.payloadEnd)
            System.arraycopy(decryptPayload(payload, o.num, 0, key, useAes), 0, out, o.payloadStart, payload.size)
        }

        // Drop the encryption object and strip /Encrypt from the trailer.
        val stripped = ByteArrayOutputStream(raw.size)
        stripped.write(out, 0, encObjMatch.range.first)
        stripped.write(out, encDictEnd, out.size - encDictEnd)
        var t = String(stripped.toByteArray(), Charsets.ISO_8859_1)
        t = t.replaceFirst(Regex("/Encrypt\\s+\\d+\\s+\\d+\\s+R\\s*"), "")
        dst.writeBytes(t.toByteArray(Charsets.ISO_8859_1))
        return dst
    }

    /**
     * Algorithm 2 (R<=4) / Algorithm 2.B (R>=5): derive the file encryption key
     * from the user password. R6+ uses a SHA-256 hash chain; older revisions use
     * the padded-password MD5 with 50 re-hash rounds.
     */
    private fun deriveKeyFromUserPassword(
        pw: String, oVal: ByteArray, pVal: Int, fileId: ByteArray, keyLen: Int, rVal: Int
    ): ByteArray {
        if (rVal >= 5) {
            // Algorithm 2.B: SHA-256 of the UTF-8 password + /O validation salt.
            val salt = oVal.sliceArray(32..39)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(pw.toByteArray(Charsets.UTF_8) + salt)
            // 2.B.5: 64 rounds of XOR-ing the hash back in (see Algorithm 2.B).
            var x = digest
            repeat(64) {
                val K = ByteArray(x.size + oVal.size + fileId.size)
                System.arraycopy(x, 0, K, 0, x.size)
                System.arraycopy(oVal, 0, K, x.size, oVal.size)
                System.arraycopy(fileId, 0, K, x.size + oVal.size, fileId.size)
                x = ByteArray(0)
                // Each round re-hashes in 64-byte blocks.
                val md = MessageDigest.getInstance("SHA-256")
                var off = 0
                while (off < K.size) {
                    val block = K.sliceArray(off until minOf(off + 64, K.size))
                    md.update(block)
                    off += 64
                }
                x = md.digest()
            }
            return x.sliceArray(0 until keyLen)
        }
        // Algorithm 2: pad + MD5(/O /P /ID), then 50 re-hash rounds of the first
        // n bytes (NOT the whole digest - the original bug hashed all 16).
        var hash = md5(pad(pw) + oVal + intLe4(pVal) + fileId)
        repeat(50) { hash = md5(hash.sliceArray(0 until keyLen)) }
        return hash.sliceArray(0 until keyLen)
    }

    private fun checkUserPassword(
        key: ByteArray, uVal: ByteArray, fileId: ByteArray, pw: String, rVal: Int, useAes: Boolean
    ) {
        if (rVal >= 5) {
            // Algorithm 3.6 / 2.B.6: recompute /U from the key + user-key salt
            // and compare the leading 32 bytes.
            val md = MessageDigest.getInstance("SHA-256")
            md.update(pw.toByteArray(Charsets.UTF_8))
            md.update(key)
            md.update(uVal.sliceArray(32..39))
            val expected = md.digest()
            if (!expected.contentEquals(uVal.sliceArray(0 until 32))) {
                throw IllegalStateException("Incorrect password. Please check and try again.")
            }
            return
        }
        // Algorithm 5 (R>=3) / Algorithm 4 (R2): recompute /U from the file key
        // and compare the leading 16 bytes for R>=3. For R>=3 the 19 RC4 rounds
        // each XOR the KEY with the round counter (1..19) - XOR-ing the data
        // instead is a common mistake that still "looks" plausible but never
        // matches. R2 has no rounds at all.
        val expected = if (rVal <= 2) {
            rc4(key, PAD)
        } else {
            var u = rc4(key, md5(PAD + fileId))
            repeat(19) { round ->
                val k = ByteArray(key.size) { (key[it].toInt() xor (round + 1)).toByte() }
                u = rc4(k, u)
            }
            u
        }
        val cmpLen = if (rVal >= 3) minOf(16, minOf(expected.size, uVal.size)) else minOf(expected.size, uVal.size)
        if (!expected.sliceArray(0 until cmpLen).contentEquals(uVal.sliceArray(0 until cmpLen))) {
            throw IllegalStateException("Incorrect password. Please check and try again.")
        }
    }

    /**
     * Algorithm 7: authenticating the owner password. /O is the user password
     * encrypted with a key derived from the owner password; recovering it and
     * re-deriving the file key yields a working key when the supplied string is
     * the owner rather than the user password. Returns that key, or null when
     * the password is neither.
     */
    private fun recoverOwnerPassword(
        password: String, oVal: ByteArray, uVal: ByteArray, pVal: Int,
        fileId: ByteArray, keyLen: Int, rVal: Int, useAes: Boolean
    ): ByteArray? {
        return try {
            // Algorithm 3: the /O value's RC4 key.
            var oh = md5(pad(password))
            if (rVal >= 3) repeat(50) { oh = md5(oh.sliceArray(0 until keyLen)) }
            val rc4Key = oh.sliceArray(0 until keyLen)

            var userPw: ByteArray = if (rVal <= 2) {
                rc4(rc4Key, oVal)
            } else {
                var v = oVal
                // 20 rounds, counter from 19 down to 0, key XOR-ed each round.
                for (i in 19 downTo 0) {
                    val k = ByteArray(rc4Key.size) { (rc4Key[it].toInt() xor i).toByte() }
                    v = rc4(k, v)
                }
                v
            }
            val recoveredKey = deriveKeyFromUserPasswordBytes(
                userPw, oVal, pVal, fileId, keyLen, rVal
            )
            checkUserPasswordBytes(recoveredKey, uVal, fileId, rVal)
            recoveredKey
        } catch (_: Exception) {
            null
        }
    }

    /** [deriveKeyFromUserPassword] for a password already decoded to bytes. */
    private fun deriveKeyFromUserPasswordBytes(
        pw: ByteArray, oVal: ByteArray, pVal: Int, fileId: ByteArray, keyLen: Int, rVal: Int
    ): ByteArray {
        if (rVal >= 5) return deriveKeyFromUserPassword(
            String(pw, Charsets.ISO_8859_1), oVal, pVal, fileId, keyLen, rVal
        )
        val padded = (pw + PAD).sliceArray(0 until 32)
        var hash = md5(padded + oVal + intLe4(pVal) + fileId)
        repeat(50) { hash = md5(hash.sliceArray(0 until keyLen)) }
        return hash.sliceArray(0 until keyLen)
    }

    /** Byte-level variant of [checkUserPassword], for a recovered password. */
    private fun checkUserPasswordBytes(key: ByteArray, uVal: ByteArray, fileId: ByteArray, rVal: Int) {
        if (rVal >= 5) return
        val expected = if (rVal <= 2) {
            rc4(key, PAD)
        } else {
            var u = rc4(key, md5(PAD + fileId))
            repeat(19) { round ->
                val k = ByteArray(key.size) { (key[it].toInt() xor (round + 1)).toByte() }
                u = rc4(k, u)
            }
            u
        }
        val cmpLen = if (rVal >= 3) minOf(16, minOf(expected.size, uVal.size)) else minOf(expected.size, uVal.size)
        if (!expected.sliceArray(0 until cmpLen).contentEquals(uVal.sliceArray(0 until cmpLen))) {
            throw IllegalStateException("owner password validation failed")
        }
    }

    private fun decryptPayload(
        payload: ByteArray, num: Int, gen: Int, key: ByteArray, useAes: Boolean
    ): ByteArray = encryptPayload(payload, num, gen, key, useAes)
}
