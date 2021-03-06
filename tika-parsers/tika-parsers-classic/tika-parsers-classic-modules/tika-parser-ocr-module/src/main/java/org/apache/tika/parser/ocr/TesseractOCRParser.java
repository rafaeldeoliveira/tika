/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.ocr;

import org.apache.commons.io.IOUtils;
import org.apache.tika.config.Field;
import org.apache.tika.config.Initializable;
import org.apache.tika.config.InitializableProblemHandler;
import org.apache.tika.config.Param;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.external.ExternalParser;
import org.apache.tika.sax.OfflineContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.XMLReaderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.tika.sax.XHTMLContentHandler.XHTML;

/**
 * TesseractOCRParser powered by tesseract-ocr engine. To enable this parser,
 * create a {@link TesseractOCRConfig} object and pass it through a
 * ParseContext. Tesseract-ocr must be installed and on system path or the path
 * to its root folder must be provided:
 * <p>
 * TesseractOCRConfig config = new TesseractOCRConfig();<br>
 * //Needed if tesseract is not on system path<br>
 * config.setTesseractPath(tesseractFolder);<br>
 * parseContext.set(TesseractOCRConfig.class, config);<br>
 * </p>
 *
 *
 */
public class TesseractOCRParser extends AbstractParser {
    public static final String TESS_META = "tess:";
    public static final Property IMAGE_ROTATION = Property.externalRealSeq(TESS_META+"rotation");
    public static final Property IMAGE_MAGICK = Property.externalBooleanSeq(TESS_META+"image_magick_processed");
    private static final String OCR = "ocr-";
    private static final Logger LOG = LoggerFactory.getLogger(TesseractOCRParser.class);

    private static volatile boolean HAS_WARNED = false;
    private static final Object[] LOCK = new Object[0];


    private static final long serialVersionUID = -8167538283213097265L;
    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(new MediaType[]{
                    MediaType.image(OCR+"png"),
                    MediaType.image(OCR+"jpeg"),
                    MediaType.image(OCR+"tiff"),
                    MediaType.image(OCR+"bmp"),
                    MediaType.image(OCR+"gif"),
                    //these are not currently covered by other parsers
                    MediaType.image("jp2"),
                    MediaType.image("jpx"),
                    MediaType.image("x-portable-pixmap"),
                    //add the ocr- versions as well
                    MediaType.image(OCR+"jp2"),
                    MediaType.image(OCR+"jpx"),
                    MediaType.image(OCR+"x-portable-pixmap"),

            })));
    private final TesseractOCRConfig defaultConfig = new TesseractOCRConfig();

    private static Map<String,Boolean> TESSERACT_PRESENT = new HashMap<>();
    static final ImagePreprocessor IMAGE_PREPROCESSOR = new ImagePreprocessor();

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        // If Tesseract is installed, offer our supported image types
        TesseractOCRConfig config = context.get(TesseractOCRConfig.class, defaultConfig);
        if (hasTesseract(config)) {
            return SUPPORTED_TYPES;
        }
        // Otherwise don't advertise anything, so the other image parsers
        //  can be selected instead
        return Collections.emptySet();
    }

    private void setEnv(TesseractOCRConfig config, ProcessBuilder pb) {
        String tessdataPrefix = "TESSDATA_PREFIX";
        Map<String, String> env = pb.environment();

        if (!config.getTessdataPath().isEmpty()) {
            env.put(tessdataPrefix, config.getTessdataPath());
        }
        else if(!config.getTesseractPath().isEmpty()) {
            env.put(tessdataPrefix, config.getTesseractPath());
        }
    }

    public boolean hasTesseract(TesseractOCRConfig config) {
        // Fetch where the config says to find Tesseract
        String tesseract = config.getTesseractPath() + getTesseractProg();

        // Have we already checked for a copy of Tesseract there?
        if (TESSERACT_PRESENT.containsKey(tesseract)) {
            return TESSERACT_PRESENT.get(tesseract);
        }
        //prevent memory bloat
        if (TESSERACT_PRESENT.size() > 100) {
            TESSERACT_PRESENT.clear();
        }
        //check that the parent directory exists
        if (! config.getTesseractPath().isEmpty() &&
                ! Files.isDirectory(Paths.get(config.getTesseractPath()))) {
            TESSERACT_PRESENT.put(tesseract, false);
            LOG.warn("You haven't specified an existing directory in " +
                    "which the tesseract binary should be found: " +
                    "(path:" + config.getTesseractPath()+")");
            return false;
        }

        // Try running Tesseract from there, and see if it exists + works
        String[] checkCmd = { tesseract };
        boolean hasTesseract = ExternalParser.check(checkCmd);
        LOG.debug("hasTesseract (path: "+checkCmd+"): "+hasTesseract);
        TESSERACT_PRESENT.put(tesseract, hasTesseract);
        return hasTesseract;
     
    }

    public void parse(Image image, ContentHandler handler, Metadata metadata, ParseContext context) throws IOException,
            SAXException, TikaException {
        TemporaryResources tmp = new TemporaryResources();
        try {
            int w = image.getWidth(null);
            int h = image.getHeight(null);
            BufferedImage bImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            File file = tmp.createTemporaryFile();
            try (OutputStream fos = new FileOutputStream(file)) {
                ImageIO.write(bImage, "png", fos);
            }
            try (TikaInputStream tis = TikaInputStream.get(file)) {
                parse(tis, handler, metadata, context);
            }
        } finally {
            tmp.dispose();
        }
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext parseContext)
            throws IOException, SAXException, TikaException {
        TesseractOCRConfig config = parseContext.get(TesseractOCRConfig.class, defaultConfig);

        // If Tesseract is not on the path with the current config, do not try to run OCR
        // getSupportedTypes shouldn't have listed us as handling it, so this should only
        //  occur if someone directly calls this parser, not via DefaultParser or similar
        if (! hasTesseract(config))
            return;

        TemporaryResources tmp = new TemporaryResources();
        try {
            TikaInputStream tikaStream = TikaInputStream.get(stream, tmp);

            //trigger the spooling to a tmp file if the stream wasn't
            //already a TikaInputStream that contained a file
            tikaStream.getPath();
            //this is the text output file name specified on the tesseract
            //commandline.  The actual output file name will have a suffix added.
            File tmpOCROutputFile = tmp.createTemporaryFile();
            XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
            xhtml.startDocument();
            parse(tikaStream, tmpOCROutputFile, xhtml, metadata, parseContext, config);
            xhtml.endDocument();
        } finally {
            tmp.dispose();
        }
    }
    
    private void parse(TikaInputStream tikaInputStream, File tmpOCROutputFile,
                       ContentHandler xhtml, Metadata metadata, ParseContext parseContext,
                       TesseractOCRConfig config)
            throws IOException, SAXException, TikaException {
        warnOnFirstParse();
        File tmpTxtOutput = null;
        try {
            Path input = tikaInputStream.getPath();
            long size = tikaInputStream.getLength();

            if (size >= config.getMinFileSizeToOcr() && size <= config.getMaxFileSizeToOcr()) {

            	// Process image
            	if (config.isEnableImageProcessing() || config.isApplyRotation()) {
                    if (! ImagePreprocessor.hasImageMagick(config)) {
                        LOG.warn("User has selected to preprocess images, but I can't find ImageMagick." +
                                "Backing off to original file.");
                        doOCR(input.toFile(), tmpOCROutputFile, config);
                    } else {
                        // copy the contents of the original input file into a temporary file
                        // which will be preprocessed for OCR

                        try (TemporaryResources tmp = new TemporaryResources()) {
                            Path tmpFile = tmp.createTempFile();
                            Files.copy(input, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                            IMAGE_PREPROCESSOR.process(tmpFile, tmpFile, metadata, config);
                            doOCR(tmpFile.toFile(), tmpOCROutputFile, config);
                        }
                    }
            	} else {
                    doOCR(input.toFile(), tmpOCROutputFile, config);
                }

                // Tesseract appends the output type (.txt or .hocr) to output file name
                tmpTxtOutput = new File(tmpOCROutputFile.getAbsolutePath() + "." +
                        config.getOutputType().toString().toLowerCase(Locale.US));

                if (tmpTxtOutput.exists()) {
                    try (InputStream is = new FileInputStream(tmpTxtOutput)) {
                        if (config.getOutputType().equals(TesseractOCRConfig.OUTPUT_TYPE.HOCR)) {
                            extractHOCROutput(is, parseContext, xhtml);
                        } else {
                            extractOutput(is, xhtml);
                        }
                    }
                }
            }
        } finally {
            if (tmpTxtOutput != null) {
                tmpTxtOutput.delete();
            }
        }
    }

    private void warnOnFirstParse() {
        if (!hasWarned()) {
            warn();
        }
    }

    /**
     * Run external tesseract-ocr process.
     *
     * @param input
     *          File to be ocred
     * @param output
     *          File to collect ocr result
     * @param config
     *          Configuration of tesseract-ocr engine
     * @throws TikaException
     *           if the extraction timed out
     * @throws IOException
     *           if an input error occurred
     */
    private void doOCR(File input, File output, TesseractOCRConfig config) throws IOException, TikaException {
        ArrayList<String> cmd = new ArrayList<>(Arrays.asList(
                config.getTesseractPath() + getTesseractProg(), input.getPath(),  output.getPath(), "-l",
                config.getLanguage(), "--psm", config.getPageSegMode()
        ));
        for (Map.Entry<String, String> entry : config.getOtherTesseractConfig().entrySet()) {
            cmd.add("-c");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }
        cmd.addAll(Arrays.asList(
                "-c", "page_separator=" + config.getPageSeparator(),
                "-c",
                (config.isPreserveInterwordSpacing())? "preserve_interword_spaces=1" : "preserve_interword_spaces=0",
                config.getOutputType().name().toLowerCase(Locale.US)
        ));
        LOG.debug("Tesseract command: " + String.join(" ", cmd));
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        setEnv(config, pb);
        final Process process = pb.start();

        process.getOutputStream().close();
        InputStream out = process.getInputStream();
        InputStream err = process.getErrorStream();

        logStream("OCR MSG", out, input);
        logStream("OCR ERROR", err, input);

        FutureTask<Integer> waitTask = new FutureTask<>(new Callable<Integer>() {
            public Integer call() throws Exception {
                return process.waitFor();
            }
        });

        Thread waitThread = new Thread(waitTask);
        waitThread.start();

        try {
            waitTask.get(config.getTimeout(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            waitThread.interrupt();
            process.destroy();
            Thread.currentThread().interrupt();
            throw new TikaException("TesseractOCRParser interrupted", e);
        } catch (ExecutionException e) {
            // should not be thrown
        } catch (TimeoutException e) {
            waitThread.interrupt();
            process.destroy();
            throw new TikaException("TesseractOCRParser timeout", e);
        }
    }

    /**
     * Reads the contents of the given stream and write it to the given XHTML
     * content handler. The stream is closed once fully processed.
     *
     * @param stream
     *          Stream where is the result of ocr
     * @param xhtml
     *          XHTML content handler
     * @throws SAXException
     *           if the XHTML SAX events could not be handled
     * @throws IOException
     *           if an input error occurred
     */
    private void extractOutput(InputStream stream, ContentHandler xhtml) throws SAXException, IOException {
        //        <div class="ocr"
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "class", "class", "CDATA", "ocr");
        xhtml.startElement(XHTML, "div", "div", attrs);
        try (Reader reader = new InputStreamReader(stream, UTF_8)) {
            char[] buffer = new char[1024];
            for (int n = reader.read(buffer); n != -1; n = reader.read(buffer)) {
                if (n > 0) {
                    xhtml.characters(buffer, 0, n);
                }
            }
        }
        xhtml.endElement(XHTML, "div", "div");
    }

    private void extractHOCROutput(InputStream is, ParseContext parseContext,
                                   ContentHandler xhtml) throws TikaException, IOException, SAXException {
        if (parseContext == null) {
            parseContext = new ParseContext();
        }

//        <div class="ocr"
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "class", "class", "CDATA", "ocr");
        xhtml.startElement(XHTML, "div", "div", attrs);
        XMLReaderUtils.parseSAX(is, new OfflineContentHandler(new HOCRPassThroughHandler(xhtml)), parseContext);
        xhtml.endElement(XHTML, "div", "div");

    }

    /**
     * Starts a thread that reads the contents of the standard output or error
     * stream of the given process to not block the process. The stream is closed
     * once fully processed.
     */
    private void logStream(final String logType, final InputStream stream, final File file) {
        new Thread() {
            public void run() {
                Reader reader = new InputStreamReader(stream, UTF_8);
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[1024];
                try {
                    for (int n = reader.read(buffer); n != -1; n = reader.read(buffer))
                        out.append(buffer, 0, n);
                } catch (IOException e) {

                } finally {
                    IOUtils.closeQuietly(stream);
                }

                LOG.debug("{}", out);
            }
        }.start();
    }

    public static String getTesseractProg() {
        return System.getProperty("os.name").startsWith("Windows") ? "tesseract.exe" : "tesseract";
    }



    private static class HOCRPassThroughHandler extends DefaultHandler {
        private final ContentHandler xhtml;
        public static final Set<String> IGNORE = unmodifiableSet(
                "html", "head", "title", "meta", "body");

        public HOCRPassThroughHandler(ContentHandler xhtml) {
            this.xhtml = xhtml;
        }

        /**
         * Starts the given element. Table cells and list items are automatically
         * indented by emitting a tab character as ignorable whitespace.
         */
        @Override
        public void startElement(
                String uri, String local, String name, Attributes attributes)
                throws SAXException {
            if (!IGNORE.contains(name)) {
                xhtml.startElement(uri, local, name, attributes);
            }
        }

        /**
         * Ends the given element. Block elements are automatically followed
         * by a newline character.
         */
        @Override
        public void endElement(String uri, String local, String name) throws SAXException {
            if (!IGNORE.contains(name)) {
                xhtml.endElement(uri, local, name);
            }
        }

        /**
         * @see <a href="https://issues.apache.org/jira/browse/TIKA-210">TIKA-210</a>
         */
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            xhtml.characters(ch, start, length);
        }

        private static Set<String> unmodifiableSet(String... elements) {
            return Collections.unmodifiableSet(
                    new HashSet<>(Arrays.asList(elements)));
        }
    }

    protected boolean hasWarned() {
        if (HAS_WARNED) {
            return true;
        }
        synchronized (LOCK) {
            if (HAS_WARNED) {
                return true;
            }
            return false;
        }
    }

    protected void warn() {
        LOG.info("Tesseract is installed and is being invoked. " +
                "This can add greatly to processing time.  If you do not want tesseract " +
                "to be applied to your files see: " +
                "https://cwiki.apache.org/confluence/display/TIKA/TikaOCR#TikaOCR-disable-ocr");
        HAS_WARNED = true;
    }

    @Field
    public void setTesseractPath(String tesseractPath) {
        defaultConfig.setTesseractPath(tesseractPath);
    }

    @Field
    public void setTessdataPath(String tessdataPath) {
        defaultConfig.setTessdataPath(tessdataPath);
    }

    @Field
    public void setLanguage(String language) {
        defaultConfig.setLanguage(language);
    }

    @Field
    public void setPageSegMode(String pageSegMode) {
        defaultConfig.setPageSegMode(pageSegMode);
    }

    @Field
    public void setMaxFileSizeToOcr(long maxFileSizeToOcr) {
        defaultConfig.setMaxFileSizeToOcr(maxFileSizeToOcr);
    }

    @Field
    public void setMinFileSizeToOcr(long minFileSizeToOcr) {
        defaultConfig.setMinFileSizeToOcr(minFileSizeToOcr);
    }

    @Field
    public void setTimeout(int timeout) {
        defaultConfig.setTimeout(timeout);
    }

    @Field
    public void setOutputType(String outputType) {
        defaultConfig.setOutputType(outputType);
    }

    @Field
    public void setPreserveInterwordSpacing(boolean preserveInterwordSpacing) {
        defaultConfig.setPreserveInterwordSpacing(preserveInterwordSpacing);
    }

    @Field
    public void setEnableImageProcessing(boolean enableImageProcessing) {
        defaultConfig.setEnableImageProcessing(enableImageProcessing);
    }

    @Field
    public void setImageMagickPath(String imageMagickPath) {
        defaultConfig.setImageMagickPath(imageMagickPath);
    }

    @Field
    public void setDensity(int density) {
        defaultConfig.setDensity(density);
    }

    @Field
    public void setDepth(int depth) {
        defaultConfig.setDepth(depth);
    }

    @Field
    public void setColorspace(String colorspace) {
        defaultConfig.setColorspace(colorspace);
    }

    @Field
    public void setFilter(String filter) {
        defaultConfig.setFilter(filter);
    }

    @Field
    public void setResize(int resize) {
        defaultConfig.setResize(resize);
    }

    @Field
    public void setApplyRotation(boolean applyRotation) {
        defaultConfig.setApplyRotation(applyRotation);
    }

    public TesseractOCRConfig getDefaultConfig() {
        return defaultConfig;
    }
}

