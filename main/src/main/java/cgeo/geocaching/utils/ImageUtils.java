package cgeo.geocaching.utils;

import cgeo.geocaching.CgeoApplication;
import cgeo.geocaching.R;
import cgeo.geocaching.models.Image;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.ContentStorage;
import cgeo.geocaching.storage.Folder;
import cgeo.geocaching.storage.LocalStorage;
import cgeo.geocaching.storage.PersistableFolder;
import cgeo.geocaching.ui.ImageGalleryView;
import cgeo.geocaching.ui.ViewUtils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Base64InputStream;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.graphics.BitmapCompat;
import androidx.core.util.Predicate;
import androidx.core.util.Supplier;
import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.caverock.androidsvg.SVG;
import com.igreenwood.loupe.Loupe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.functions.Consumer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;

public final class ImageUtils {

    private static final String OFFLINE_LOG_IMAGE_PREFIX = "cgeo-image-";

    // Images whose URL contains one of those patterns will not be available on the Images tab
    // for opening into an external application.
    private static final String[] NO_EXTERNAL = {"geocheck.org"};

    private static final Pattern IMG_TAG = Pattern.compile(Pattern.quote("<img") + "\\s[^>]*?" + Pattern.quote("src=\"") + "(.+?)" + Pattern.quote("\""));
    private static final Pattern IMG_URL = Pattern.compile("(https?://\\S*\\.(jpeg|jpe|jpg|png|webp|gif|svg)((\\?|#|$|\\)|])\\S*)?)");
    static final Pattern PATTERN_GC_HOSTED_IMAGE = Pattern.compile("^https?://img(?:cdn)?\\.geocaching\\.com(?::443)?(?:/[a-z/]*)?/([^/]*)");
    static final Pattern PATTERN_GC_HOSTED_IMAGE_S3 = Pattern.compile("^https?://s3\\.amazonaws\\.com(?::443)?/gs-geo-images/(.*?)(?:_l|_d|_sm|_t)?(\\.jpg|jpeg|png|gif|bmp|JPG|JPEG|PNG|GIF|BMP)");

    public static class ImageFolderCategoryHandler implements ImageGalleryView.EditableCategoryHandler {

        private final Folder folder;

        public ImageFolderCategoryHandler(final String geocode) {
            folder = getSpoilerImageFolder(geocode);
        }

        @Override
        public Collection<Image> getAllImages() {
            return CollectionStream.of(ContentStorage.get().list(folder))
                    .map(fi -> new Image.Builder().setUrl(fi.uri)
                            .setTitle(getTitleFromName(fi.name))
                            .setCategory(Image.ImageCategory.OWN)
                            .setContextInformation("Stored: " + Formatter.formatDateTime(fi.lastModified))
                            .build()).toList();
        }

        @Override
        public Collection<Image> add(final Collection<Image> images) {
            final Collection<Image> resultCollection = new ArrayList<>();
            for (Image img : images) {
                final String filename = getFilenameFromUri(img.getUri());
                final String title = getTitleFromName(filename);
                final Uri newUri = ContentStorage.get().copy(img.getUri(), folder, FileNameCreator.forName(filename), false);
                resultCollection.add(img.buildUpon().setUrl(newUri).setTitle(title)
                        .setCategory(Image.ImageCategory.OWN)
                        .setContextInformation("Stored: " + Formatter.formatDateTime(System.currentTimeMillis()))
                        .build());
            }
            return resultCollection;
        }

        @Override
        public Image setTitle(final Image image, final String title) {
            final String newFilename = getNewFilename(getFilenameFromUri(image.getUri()), title);
            final Uri newUri = ContentStorage.get().rename(image.getUri(), FileNameCreator.forName(newFilename));
            if (newUri == null) {
                return null;
            }
            return image.buildUpon().setUrl(newUri).setTitle(getTitleFromName(getFilenameFromUri(newUri))).build();
        }

        @Override
        public void delete(final Image image) {
            ContentStorage.get().delete(image.uri);
        }

        private String getFilenameFromUri(final Uri uri) {
            String filename = ContentStorage.get().getName(uri);
            if (filename == null) {
                filename = UriUtils.getLastPathSegment(uri);
            }
            if (!filename.contains(".")) {
                filename += ".jpg";
            }
            return filename;
        }

        private String getTitleFromName(final String filename) {
            String title = filename == null ? "-" : filename;
            final int idx = title.lastIndexOf(".");
            if (idx > 0) {
                title = title.substring(0, idx);
            }
            return title;
        }

        private String getNewFilename(final String oldFilename, final String newTitle) {
            final int idx = oldFilename.lastIndexOf(".");
            final String suffix = idx >= 0 ? "." + oldFilename.substring(idx + 1) : "";
            return newTitle + suffix;
        }
    }


    private ImageUtils() {
        // Do not let this class be instantiated, this is a utility class.
    }

    /**
     * Scales a bitmap to the device display size. Also ensures a minimum image size
     *
     * @param image The image Bitmap representation to scale
     * @return BitmapDrawable The scaled image
     */
    @NonNull
    public static BitmapDrawable scaleBitmapToDisplay(@NonNull final Bitmap image) {

        //special case: 1x1 images used for layouting
        final boolean isOneToOne = image.getHeight() == 1 && image.getWidth() == 1;

        final Point displaySize = DisplayUtils.getDisplaySize();
        final int maxWidth = displaySize.x - 25;
        final int maxHeight = displaySize.y - 25;
        final int minWidth = isOneToOne ? 1 : ViewUtils.spToPixel(20);
        final int minHeight = isOneToOne ? 1 : ViewUtils.spToPixel(20);
        return scaleBitmapTo(image, maxWidth, maxHeight, minWidth, minHeight);
    }

    /**
     * Scales a bitmap to the given bounds if it is larger, otherwise returns the original bitmap (except when "force" is set to true)
     *
     * @param image The bitmap to scale
     * @return BitmapDrawable The scaled image
     */
    @NonNull
    private static BitmapDrawable scaleBitmapTo(@NonNull final Bitmap image, final int maxWidth, final int maxHeight) {
        return scaleBitmapTo(image, maxWidth, maxHeight, -1, -1);
    }

    @NonNull
    private static BitmapDrawable scaleBitmapTo(@NonNull final Bitmap image, final int maxWidth, final int maxHeight, final int minWidth, final int minHeight) {
        final Application app = CgeoApplication.getInstance();
        Bitmap result = image;
        final ImmutableTriple<Integer, Integer, Boolean> scaledSize = calculateScaledImageSizes(image.getWidth(), image.getHeight(), maxWidth, maxHeight, minWidth, minHeight);

        if (scaledSize.right) {
            if (image.getConfig() == null) {
                // see #15526: surprisingly, Bitmaps with getConfig() == null may causing a NPE when using BitmapCompat...
                result = Bitmap.createScaledBitmap(image, scaledSize.left, scaledSize.middle, true);
            } else {
                result = BitmapCompat.createScaledBitmap(image, scaledSize.left, scaledSize.middle, null, true);
            }
        }

        final BitmapDrawable resultDrawable = new BitmapDrawable(app.getResources(), result);
        resultDrawable.setBounds(new Rect(0, 0, scaledSize.left, scaledSize.middle));

        return resultDrawable;
    }

    public static ImmutableTriple<Integer, Integer, Boolean> calculateScaledImageSizes(final int originalWidth, final int originalHeight, final int maxWidth, final int maxHeight) {
        return calculateScaledImageSizes(originalWidth, originalHeight, maxWidth, maxHeight, -1, -1);
    }

    public static ImmutableTriple<Integer, Integer, Boolean> calculateScaledImageSizes(final int originalWidth, final int originalHeight, final int maxWidth, final int maxHeight, final int minWidth, final int minHeight) {

        int width = originalWidth;
        int height = originalHeight;
        final int realMaxWidth = maxWidth <= 0 ? width : maxWidth;
        final int realMaxHeight = maxHeight <= 0 ? height : maxHeight;
        final int realMinWidth = minWidth <= 0 ? width : minWidth;
        final int realMinHeight = minHeight <= 0 ? height : minHeight;
        final boolean imageTooLarge = width > realMaxWidth || height > realMaxHeight;
        final boolean imageTooSmall = width < realMinWidth || height < realMinHeight;

        if (!imageTooLarge && !imageTooSmall) {
            return new ImmutableTriple<>(width, height, false);
        }

        final double ratio = imageTooLarge ?
                Math.min((double) realMaxHeight / (double) height, (double) realMaxWidth / (double) width) :
                Math.max((double) realMinHeight / (double) height, (double) realMinWidth / (double) width) ;

        width = (int) Math.ceil(width * ratio);
        height = (int) Math.ceil(height * ratio);
        return new ImmutableTriple<>(width, height, true);
    }

    /**
     * Store a bitmap to uri.
     *
     * @param bitmap    The bitmap to store
     * @param format    The image format
     * @param quality   The image quality
     * @param targetUri Path to store to
     */
    public static void storeBitmap(final Bitmap bitmap, final Bitmap.CompressFormat format, final int quality, final Uri targetUri) {
        final BufferedOutputStream bos = null;
        try {
            bitmap.compress(format, quality, CgeoApplication.getInstance().getApplicationContext().getContentResolver().openOutputStream(targetUri));
        } catch (final IOException e) {
            Log.e("ImageHelper.storeBitmap", e);
        } finally {
            IOUtils.closeQuietly(bos);
        }
    }

    @Nullable
    private static File compressImageToFile(@NonNull final Uri imageUri) {
        return scaleAndCompressImageToTemporaryFile(imageUri, -1, 100);
    }

    @Nullable
    public static File scaleAndCompressImageToTemporaryFile(@NonNull final Uri imageUri, final int maxXY, final int compressQuality) {

        final Bitmap image = readImage(imageUri);
        if (image == null) {
            return null;
        }

        final File targetFile = FileUtils.getUniqueNamedFile(new File(LocalStorage.getExternalPrivateCgeoDirectory(), "temporary_image.jpg"));
        final Uri newImageUri = Uri.fromFile(targetFile);
        if (newImageUri == null) {
            Log.e("ImageUtils.readScaleAndWriteImage: unable to write scaled image");
            return null;
        }

        final Bitmap scaledImage = scaleBitmapTo(image, maxXY, maxXY).getBitmap();
        final ViewOrientation orientation = getImageOrientation(imageUri);
        final Bitmap orientedImage = orientation.isNormal() ? scaledImage : Bitmap.createBitmap(scaledImage, 0, 0, scaledImage.getWidth(), scaledImage.getHeight(), orientation.createOrientationCalculationMatrix(), true);

        storeBitmap(orientedImage, Bitmap.CompressFormat.JPEG, compressQuality <= 0 ? 75 : compressQuality, newImageUri);

        return targetFile;
    }

    @Nullable
    private static Bitmap readImage(final Uri imageUri) {
        try (InputStream imageStream = openImageStreamIfLocal(imageUri)) {
            if (imageStream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(imageStream);
        } catch (final IOException e) {
            Log.e("ImageUtils.readDownsampledImage(decode)", e);
        }
        return null;
    }

    public static ViewOrientation getImageOrientation(@NonNull final Uri imageUri) {
        return ViewOrientation.ofExif(getExif(imageUri));
    }

    public static ExifInterface getExif(@NonNull final Uri imageUri) {
        try (InputStream imageStream = openImageStreamIfLocal(imageUri)) {
            if (imageStream != null) {
                return new ExifInterface(imageStream);
            }
        } catch (final IOException e) {
            Log.e("ImageUtils.getImageOrientation(ExifIf)", e);
        }
        return null;
    }

    @Nullable
    public static ImmutablePair<Integer, Integer> getImageSize(@Nullable final Uri imageData) {
        if (imageData == null) {
            return null;
        }
        try (InputStream imageStream = openImageStreamIfLocal(imageData)) {
            if (imageStream == null) {
                return null;
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(imageStream, null, bounds);
            if (bounds.outWidth == -1 || bounds.outHeight < 0) {
                return null;
            }
            return new ImmutablePair<>(bounds.outWidth, bounds.outHeight);
        } catch (IOException e) {
            Log.e("ImageUtils.getImageSize", e);
        }
        return null;
    }

    /**
     * Check if the URL contains one of the given substrings.
     *
     * @param url      the URL to check
     * @param patterns a list of substrings to check against
     * @return <tt>true</tt> if the URL contains at least one of the patterns, <tt>false</tt> otherwise
     */
    public static boolean containsPattern(final String url, final String[] patterns) {
        for (final String entry : patterns) {
            if (StringUtils.containsIgnoreCase(url, entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decode a base64-encoded string and save the result into a file.
     *
     * @param inString the encoded string
     * @param outFile  the file to save the decoded result into
     */
    public static void decodeBase64ToFile(final String inString, final File outFile) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(outFile);
            decodeBase64ToStream(inString, out);
        } catch (final IOException e) {
            Log.e("HtmlImage.decodeBase64ToFile: cannot write file for decoded inline image", e);
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * Decode a base64-encoded string and save the result into a stream.
     *
     * @param inString the encoded string
     * @param out      the stream to save the decoded result into
     */
    public static void decodeBase64ToStream(final String inString, final OutputStream out) throws IOException {
        Base64InputStream in = null;
        try {
            in = new Base64InputStream(new ByteArrayInputStream(inString.getBytes(StandardCharsets.US_ASCII)), Base64.DEFAULT);
            IOUtils.copy(in, out);
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    @NonNull
    public static BitmapDrawable getTransparent1x1Drawable(final Resources res) {
        return new BitmapDrawable(res, BitmapFactory.decodeResource(res, R.drawable.image_no_placement));
    }


    @NonNull
    public static String imageUrlForSpoilerCompare(@Nullable final String url) {
        if (url == null) {
            return "";
        }
        return StringUtils.defaultString(Uri.parse(url).getLastPathSegment());
    }

    public static Predicate<String> getImageContainsPredicate(final Collection<Image> images) {
        final Set<String> urls = images == null ? Collections.emptySet() :
            images.stream().map(img -> img == null ? "" : imageUrlForSpoilerCompare(img.getUrl())).collect(Collectors.toSet());
        return url -> urls.contains(imageUrlForSpoilerCompare(url));
    }

    /**
     * Add images present in plain text to the existing collection.
     *
     * @param texts    plain texts to be searched for image URLs
     */
    public static List<Image> getImagesFromText(final BiConsumer<String, Image.Builder> modifier, final String ... texts) {
        final List<Image> result = new ArrayList<>();
        if (null == texts) {
            return result;
        }
        for (final String text : texts) {
            //skip null or empty texts
            if (StringUtils.isBlank(text)) {
                continue;
            }
            final Matcher m = IMG_URL.matcher(text);

            while (m.find()) {
                if (m.groupCount() >= 1) {
                    final String imgUrl = m.group(1);
                    if (StringUtils.isBlank(imgUrl)) {
                        continue;
                    }
                    final Image.Builder builder = new Image.Builder().setUrl(imgUrl, "https");
                    if (modifier != null) {
                        modifier.accept(imgUrl, builder);
                    }
                    result.add(builder.build());
                }
            }

        }
        return result;
    }

    /**
     * Add images present in the HTML description to the existing collection.
     *
     * @param htmlText the HTML description to be parsed, can be repeated
     */
    public static List<Image> getImagesFromHtml(final BiConsumer<String, Image.Builder> modifier, final String... htmlText) {
        final List<Image> result = new ArrayList<>();
        forEachImageUrlInHtml(source -> {
                if (canBeOpenedExternally(source)) {
                    final Image.Builder builder = new Image.Builder()
                            .setUrl(source, "https");
                    if (modifier != null) {
                        modifier.accept(source, builder);
                    }
                    result.add(builder.build());
                }
            }, htmlText);
        return result;
    }

    public static void deduplicateImageList(final Collection<Image> list) {
        final Set<String> urls = new HashSet<>();
        final Iterator<Image> it = list.iterator();
        while (it.hasNext()) {
            final Image img = it.next();
            final String url = ImageUtils.imageUrlForSpoilerCompare(img.getUrl());
            if (urls.contains(url)) {
                it.remove();
            }
            urls.add(url);
        }
    }

    public static void forEachImageUrlInHtml(@Nullable final androidx.core.util.Consumer<String> callback, @Nullable final String ... htmlText) {
        //shortcut for nulls
        if (htmlText == null || callback == null) {
            return;
        }
        try (ContextLogger cLog = new ContextLogger(Log.LogLevel.DEBUG, "forEachImageUrlInHtml")) {
            final boolean debug = Log.isDebug();
            for (final String text : htmlText) {
                //skip null or empty texts
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                final AtomicInteger count = debug ? new AtomicInteger(0) : null;
                if (debug) {
                    cLog.add("size:" + text.length());
                }
                //Performance warning: do NOT use HtmlCompat.fromHtml here - it is far too slow
                //Example: scanning listing of GC89AXV (approx. 900KB text, 4002 images inside)
                //-> takes > 4000ms with HtmlCompat.fromHtml, but only about 50-150ms with regex approach
                final Matcher m = IMG_TAG.matcher(text);
                while (m.find()) {
                    if (m.groupCount() == 1) {
                        if (debug) {
                            count.addAndGet(1);
                        }
                        callback.accept(m.group(1));
                    }
                }
                if (debug) {
                    cLog.add("#found:" + count);
                }
            }
        }
    }

    @NonNull
    public static String getGCFullScaleImageUrl(@NonNull final String imageUrl) {
        // Images from geocaching.com exist in original + 4 generated sizes: large, display, small, thumb
        // Manipulate the URL to load the requested size.
        final GCImageSize preferredSize = ImageUtils.GCImageSize.valueOf(Settings.getString(R.string.pref_gc_imagesize, "ORIGINAL"));
        MatcherWrapper matcherViewstates = new MatcherWrapper(PATTERN_GC_HOSTED_IMAGE, imageUrl);
        if (matcherViewstates.find()) {
            return "https://img.geocaching.com/" + preferredSize.getPathname() + matcherViewstates.group(1);
        }
        matcherViewstates = new MatcherWrapper(PATTERN_GC_HOSTED_IMAGE_S3, imageUrl);
        if (matcherViewstates.find()) {
            return "https://img.geocaching.com/" + preferredSize.getPathname() + matcherViewstates.group(1) + matcherViewstates.group(2);
            //return "https://s3.amazonaws.com/gs-geo-images/" + matcherViewstates.group(1) + preferredSize.getSuffix() + matcherViewstates.group(2);
        }
        return imageUrl;
    }

    public enum GCImageSize {
        ORIGINAL("", ""),
        LARGE("_l", "large/"),
        DISPLAY("_d", "display/"),
        SMALL("_sm", "small/"),
        THUMB("_t", "thumb/");

        private final String suffix;
        private final String pathname;

        GCImageSize(final String suffix, final String pathname) {
            this.suffix = suffix;
            this.pathname = pathname;
        }

        public String getPathname() {
            return pathname;
        }

        public String getSuffix() {
            return suffix;
        }
    }

    /**
     * Container which can hold a drawable (initially an empty one) and get a newer version when it
     * becomes available. It also invalidates the view the container belongs to, so that it is
     * redrawn properly.
     * <p/>
     * When a new version of the drawable is available, it is put into a queue and, if needed (no other elements
     * waiting in the queue), a refresh is launched on the UI thread. This refresh will empty the queue (including
     * elements arrived in the meantime) and ensures that the view is uploaded only once all the queued requests have
     * been handled.
     */
    public static class ContainerDrawable extends BitmapDrawable implements Consumer<Drawable> {
        private static final Object lock = new Object(); // Used to lock the queue to determine if a refresh needs to be scheduled
        private static final LinkedBlockingQueue<ImmutablePair<ContainerDrawable, Drawable>> REDRAW_QUEUE = new LinkedBlockingQueue<>();
        private static final Set<TextView> VIEWS = new HashSet<>();  // Modified only on the UI thread, from redrawQueuedDrawables
        private static final Runnable REDRAW_QUEUED_DRAWABLES = ContainerDrawable::redrawQueuedDrawables;

        private Drawable drawable;
        protected final WeakReference<TextView> viewRef;

        @SuppressWarnings("deprecation")
        public ContainerDrawable(@NonNull final TextView view, final Observable<? extends Drawable> drawableObservable) {
            viewRef = new WeakReference<>(view);
            drawable = null;
            setBounds(0, 0, 0, 0);
            drawableObservable.subscribe(this);
        }

        @Override
        public final void draw(final Canvas canvas) {
            if (drawable != null) {
                drawable.draw(canvas);
            }
        }

        @Override
        public final void accept(final Drawable newDrawable) {
            final boolean needsRedraw;
            synchronized (lock) {
                // Check for emptiness inside the call to match the behaviour in redrawQueuedDrawables().
                needsRedraw = REDRAW_QUEUE.isEmpty();
                REDRAW_QUEUE.add(ImmutablePair.of(this, newDrawable));
            }
            if (needsRedraw) {
                AndroidSchedulers.mainThread().scheduleDirect(REDRAW_QUEUED_DRAWABLES);
            }
        }

        /**
         * Update the container with the new drawable. Called on the UI thread.
         *
         * @param newDrawable the new drawable
         * @return the view to update or <tt>null</tt> if the view is not alive anymore
         */
        protected TextView updateDrawable(final Drawable newDrawable) {
            setBounds(0, 0, newDrawable.getIntrinsicWidth(), newDrawable.getIntrinsicHeight());
            drawable = newDrawable;
            return viewRef.get();
        }

        private static void redrawQueuedDrawables() {
            if (!REDRAW_QUEUE.isEmpty()) {
                // Add a small margin so that drawables arriving between the beginning of the allocation and the draining
                // of the queue might be absorbed without reallocation.
                final List<ImmutablePair<ContainerDrawable, Drawable>> toRedraw = new ArrayList<>(REDRAW_QUEUE.size() + 16);
                synchronized (lock) {
                    // Empty the queue inside the lock to match the check done in call().
                    REDRAW_QUEUE.drainTo(toRedraw);
                }
                for (final ImmutablePair<ContainerDrawable, Drawable> redrawable : toRedraw) {
                    final TextView view = redrawable.left.updateDrawable(redrawable.right);
                    if (view != null) {
                        VIEWS.add(view);
                    }
                }
                for (final TextView view : VIEWS) {
                    // This forces the relayout of the text around the updated images.
                    view.setText(view.getText());
                }
                VIEWS.clear();
            }
        }

    }

    /**
     * Image that automatically scales to fit a line of text in the containing {@link TextView}.
     */
    public static final class LineHeightContainerDrawable extends ContainerDrawable {
        public LineHeightContainerDrawable(@NonNull final TextView view, final Observable<? extends Drawable> drawableObservable) {
            super(view, drawableObservable);
        }

        @Override
        protected TextView updateDrawable(final Drawable newDrawable) {
            final TextView view = super.updateDrawable(newDrawable);
            if (view != null) {
                setBounds(scaleImageToLineHeight(newDrawable, view));
            }
            return view;
        }
    }

    public static boolean canBeOpenedExternally(final String source) {
        return !containsPattern(source, NO_EXTERNAL);
    }

    @NonNull
    public static Rect scaleImageToLineHeight(final Drawable drawable, final TextView view) {
        final int lineHeight = (int) (view.getLineHeight() * 0.8);
        final int width = drawable.getIntrinsicWidth() * lineHeight / drawable.getIntrinsicHeight();
        return new Rect(0, 0, width, lineHeight);
    }

    @NonNull
    public static Bitmap convertToBitmap(@NonNull final Drawable drawable) {

        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        // handle solid colors, which have no width
        int width = drawable.getIntrinsicWidth();
        width = width > 0 ? width : 1;
        int height = drawable.getIntrinsicHeight();
        height = height > 0 ? height : 1;

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    @Nullable
    private static InputStream openImageStreamIfLocal(final Uri imageUri) {
        if (UriUtils.isLocalUri(imageUri)) {
            return ContentStorage.get().openForRead(imageUri, true);
        }
        return null;
    }

    public static boolean deleteImage(final Uri uri) {
        if (uri != null && StringUtils.isNotBlank(uri.toString())) {
            return ContentStorage.get().delete(uri);
        }
        return false;
    }

    /**
     * Creates a new image Uri for a public image.
     * Just the filename and uri is created, no data is stored.
     *
     * @param geocode an identifier which will become part of the filename. Might be e.g. the gccode
     * @return left: created filename, right: uri for the image
     */
    public static ImmutablePair<String, Uri> createNewPublicImageUri(final String geocode) {

        final String imageFileName = FileNameCreator.OFFLINE_LOG_IMAGE.createName(geocode == null ? "x" : geocode);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
            return new ImmutablePair<>(imageFileName,
                    CgeoApplication.getInstance().getApplicationContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values));
        }

        //the following only works until Version Q
        final File imageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        final File image = new File(imageDir, imageFileName);

        //go through file provider so we can share the Uri with e.g. camera app
        return new ImmutablePair<>(imageFileName, FileProvider.getUriForFile(
                CgeoApplication.getInstance().getApplicationContext(),
                CgeoApplication.getInstance().getApplicationContext().getString(R.string.file_provider_authority),
                image));
    }

    public static Image toLocalLogImage(final String geocode, final Uri imageUri) {
        //create new image
        final String imageFileName = FileNameCreator.OFFLINE_LOG_IMAGE.createName(geocode == null ? "shared" : geocode);
        final Folder folder = Folder.fromFile(getFileForOfflineLogImage(imageFileName).getParentFile());
        final Uri targetUri = ContentStorage.get().copy(imageUri, folder, FileNameCreator.forName(imageFileName), false);

        return new Image.Builder().setUrl(targetUri).build();
    }

    public static void deleteOfflineLogImagesFor(final String geocode, final List<Image> keep) {
        if (geocode == null) {
            return;
        }
        final Set<String> filenamesToKeep = CollectionStream.of(keep).map(i -> i.getFile() == null ? null : i.getFile().getName()).toSet();
        final String fileNamePraefix = OFFLINE_LOG_IMAGE_PREFIX + geocode;
        CollectionStream.of(LocalStorage.getOfflineLogImageDir(geocode).listFiles())
                .filter(f -> f.getName().startsWith(fileNamePraefix) && !filenamesToKeep.contains(f.getName()))
                .forEach(File::delete);
    }

    public static File getFileForOfflineLogImage(final String imageFileName) {
        //extract geocode
        String geocode = null;
        if (imageFileName.startsWith(OFFLINE_LOG_IMAGE_PREFIX)) {
            final int idx = imageFileName.indexOf("-", OFFLINE_LOG_IMAGE_PREFIX.length());
            if (idx >= 0) {
                geocode = imageFileName.substring(OFFLINE_LOG_IMAGE_PREFIX.length(), idx);
            }
        }
        return new File(LocalStorage.getOfflineLogImageDir(geocode), imageFileName);
    }

    /**
     * adjusts a previously stored offline log image uri to maybe changed realities on the file system
     */
    public static Uri adjustOfflineLogImageUri(final Uri imageUri) {
        if (imageUri == null) {
            return imageUri;
        }

        // if image folder was moved, try to find image in actual folder using its name
        if (UriUtils.isFileUri(imageUri)) {
            final File imageFileCandidate = new File(imageUri.getPath());
            if (!imageFileCandidate.isFile()) {
                return Uri.fromFile(getFileForOfflineLogImage(imageFileCandidate.getName()));
            }
        }

        return imageUri;
    }

    public static void viewImageInStandardApp(final Activity activity, final Uri imgUri, final String geocode) {

        if (activity == null || imgUri == null) {
            return;
        }

        final Uri imageFileUri = getLocalImageFileUriForSharing(activity, imgUri, geocode);
        if (imageFileUri == null) {
            return;
        }

        try {
            final Intent intent = new Intent().setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(imageFileUri, mimeTypeForUrl(imageFileUri.toString()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (final Exception e) {
            Log.e("ImageUtils.viewImageInStandardApp", e);
        }
    }

    /**
     * gets or creates local file and shareable Uri for given image. Returns null if creation fails
     */
    @Nullable
    public static Uri getLocalImageFileUriForSharing(final Context context, final Uri imgUri, final String geocode) {

        if (imgUri == null) {
            return null;
        }

        final String storeCode = StringUtils.isBlank(geocode) ? "shared" : geocode;

        Uri imageUri = imgUri;

        if (!UriUtils.isFileUri(imgUri)) {
            //try to find local file in cache image storage
            final File file = LocalStorage.getGeocacheDataFile(storeCode, imgUri.toString(), true, true);
            if (file.exists()) {
                imageUri = Uri.fromFile(file);
            }
        }

        File file = UriUtils.isFileUri(imageUri) ? UriUtils.toFile(imageUri) : null;
        if (file == null || !file.exists()) {
            file = compressImageToFile(imageUri);
        }
        if (file == null) {
            return null;
        }
        file.deleteOnExit();
        final String authority = context.getString(R.string.file_provider_authority);
        return FileProvider.getUriForFile(context, authority, file);

    }

    private static String mimeTypeForUrl(final String url) {
        return StringUtils.defaultString(UriUtils.getMimeType(Uri.parse(url)), "image/*");
    }

    public static void initializeImageGallery(final ImageGalleryView imageGallery, final String geocode, final Collection<Image> images, final boolean showOwnCategory) {
        imageGallery.clear();
        imageGallery.setup(geocode);
        imageGallery.registerCallerActivity();
        if (geocode != null && showOwnCategory) {
            imageGallery.setEditableCategory(Image.ImageCategory.OWN.getI18n(), new ImageFolderCategoryHandler(geocode));
        }
        if (images != null) {
            //pre-create all contained categories to be in control of their sort order
            images.stream().map(img -> img.category)
                    .distinct().sorted().forEach(cat -> imageGallery.createCategory(
                            cat == Image.ImageCategory.UNCATEGORIZED ? null : cat.getI18n(), false));
            //add the images
            imageGallery.addImages(images);
        }
    }

    /**
     * transforms an ImageView in a zoomable, pinchable ImageView. This method uses Loupe under the hood
     * @param activity activity where the view resides in
     * @param imageView imageView to make zoomable/pinchable
     * @param imageContainer container around the imageView. See loupe doc for details
     * @param onFlingUpDown optional: action to happen on fling down or fling up
     * @param onSingleTap optiona: action to happen on single tap. Note that this action is registered / exceuted for whole activity
     */
    @SuppressLint("ClickableViewAccessibility") //this is due to Loupe hack
    public static void createZoomableImageView(final Activity activity, final ImageView imageView, final ViewGroup imageContainer,
                                               final Runnable onFlingUpDown, final Runnable onSingleTap) {
        final Loupe loupe = new Loupe(imageView, imageContainer);
        if (onFlingUpDown != null) {
            loupe.setOnViewTranslateListener(new Loupe.OnViewTranslateListener() {
                @Override
                public void onStart(@NonNull final ImageView imageView) {
                    //empty on purpose
                }

                @Override
                public void onViewTranslate(@NonNull final ImageView imageView, final float v) {
                    //empty on purpose
                }

                @Override
                public void onDismiss(@NonNull final ImageView imageView) {
                    //this method is called on "fling down" or "fling up"
                    onFlingUpDown.run();
                }

                @Override
                public void onRestore(@NonNull final ImageView imageView) {
                    //empty on purpose
                }
            });
        }

        if (onSingleTap != null) {
            //Loupe is unable to detect single clicks (see https://github.com/igreenwood/loupe/issues/25)
            //As a workaround we register a second GestureDetector on top of the one installed by Loupe to detect single taps
            //Workaround START
            final GestureDetector singleTapDetector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(@NonNull final MotionEvent e) {
                    //Logic to happen on single tap
                    onSingleTap.run();
                    return true;
                }
            });
            //Registering an own touch listener overrides the TouchListener registered by Loupe
            imageContainer.setOnTouchListener((v, event) -> {
                //perform singleTap detection
                singleTapDetector.onTouchEvent(event);
                //pass through event to Loupe so it handles all other gestures correctly
                return loupe.onTouch(v, event);
            });
        }
        //Workaround END
    }

    @Nullable
    public static Bitmap rotateBitmap(@Nullable final Bitmap bm, final float angleInDegree) {
        if (bm == null || angleInDegree == 0f || angleInDegree % 360 == 0f) {
            return bm;
        }
        final int h = bm.getHeight();
        final int w = bm.getWidth();
        final Matrix matrix = new Matrix();
        matrix.postRotate(angleInDegree); //, w / 2f, h / 2f);
        return Bitmap.createBitmap(bm, 0, 0, w, h, matrix, true);
    }

    public static Intent createExternalEditImageIntent(final Context ctx, final Uri imageUri) {
        final Intent intent = new Intent(Intent.ACTION_EDIT);
        //final Uri uri = ContentStorage.get().copy(image.uri, PersistableFolder.GPX.getFolder(), FileNameCreator.forName("test.jpg"), false);
        final Uri uri = UriUtils.toContentUri(ctx, imageUri);
        intent.setDataAndType(uri, "image/*");
        intent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        return Intent.createChooser(intent, null);
    }

    public static Bitmap createBitmapForText(final String text, final float textSizeInDp, @Nullable final Typeface typeface, @ColorInt final int textColor, @ColorInt final int fillColor) {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(ViewUtils.dpToPixelFloat(textSizeInDp));
        paint.setColor(textColor);
        if (typeface != null) {
            paint.setTypeface(typeface);
        }

        paint.setTextAlign(Paint.Align.LEFT);
        final float baseline = -paint.ascent(); // ascent() is negative
        final int width = (int) (paint.measureText(text) + 0.5f); // round
        final int height = (int) (baseline + paint.descent() + 0.5f);
        final Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(image);
        if (fillColor != Color.TRANSPARENT) {
            canvas.drawColor(fillColor, PorterDuff.Mode.SRC_OVER);
        }
        canvas.drawText(text, 0, baseline, paint);
        return image;
    }

    /** tries to read an image from a stream supplier. Returns null if this fails. Supports normal Android bitmaps and SVG. */
    @Nullable
    public static Bitmap readImageFromStream(@NonNull final Supplier<InputStream> streamSupplier, @Nullable final BitmapFactory.Options bfOptions, @Nullable final Object logId) {
        Bitmap image = null;
        //try reading as normal bitmap first
        try (InputStream is = streamSupplier.get()) {
            if (is == null) {
                Log.w("Can't open '" + logId + "' for image reading, maybe is doesn't exist?");
                return null;
            }
            image = BitmapFactory.decodeStream(is, null, bfOptions);
        } catch (Exception ex) {
            Log.w("Error processing '" + logId + "'", ex);
        }

        if (image == null) {
            //try to read as SVG
            try (InputStream is = streamSupplier.get()) {
                if (is == null) {
                    Log.w("Can't open '" + logId + "' for image reading, maybe is doesn't exist?");
                    return null;
                }
                image = ImageUtils.readSVGImageFromStream(is, logId);
            } catch (Exception ex) {
                Log.w("Error processing '" + logId + "'", ex);
            }
        }
        return image;
    }

    /** Reads an Inputstream as SVG image. Stream is NOT CLOSED! If an error happens on read, null is returned */
    @Nullable
    private static Bitmap readSVGImageFromStream(@NonNull final InputStream is, @Nullable final Object logId) {
        //For documentation of SVG lib see https://bigbadaboom.github.io/androidsvg/
        try {
            final SVG svg = SVG.getFromInputStream(is);

            // Create a canvas to draw onto
            final int width = svg.getDocumentWidth() == -1 ? 200 : (int) Math.ceil(svg.getDocumentWidth());
            final int height = svg.getDocumentHeight() == -1 ? 200 : (int) Math.ceil(svg.getDocumentHeight());
            final Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas bmcanvas = new Canvas(image);

            // Clear background to white
            bmcanvas.drawRGB(255, 255, 255);

            // Render our document onto our canvas
            svg.renderToCanvas(bmcanvas);
            return image;
        } catch (Exception es) {
            Log.w("Problem parsing '" + logId + "' as SVG", es);
        }
        return null;
    }

    public static Folder getSpoilerImageFolder(final String geocode) {
        if (geocode == null) {
            return null;
        }
        final String suffix = StringUtils.right(geocode, 2);
        return Folder.fromFolder(PersistableFolder.SPOILER_IMAGES.getFolder(),
                suffix.substring(1) + "/" + suffix.charAt(0) + "/" + geocode);
    }

}
