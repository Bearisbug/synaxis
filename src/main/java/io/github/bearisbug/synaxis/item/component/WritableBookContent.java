package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.List;

public record WritableBookContent(List<FilteredText<String>> pages) {
    public static final WritableBookContent EMPTY = new WritableBookContent(List.of());

    public static final NetworkBuffer.Type<WritableBookContent> NETWORK_TYPE = NetworkBufferTemplate.template(
            FilteredText.STRING_NETWORK_TYPE.list(100), WritableBookContent::pages,
            WritableBookContent::new);
    public static final Codec<WritableBookContent> CODEC = StructCodec.struct(
            "pages", FilteredText.STRING_CODEC.list().optional(List.of()), WritableBookContent::pages,
            WritableBookContent::new);

    // The 26.2 client decodes at most 100 pages of at most 1024 characters and drops the connection over more
    public WritableBookContent {
        pages = List.copyOf(pages);
        Check.argCondition(pages.size() > 100, "A book and quill holds at most 100 pages, got {0}", pages.size());
        for (int i = 0; i < pages.size(); i++) {
            final String filtered = pages.get(i).filtered();
            Check.argCondition(pages.get(i).text().length() > 1024 || (filtered != null && filtered.length() > 1024),
                    "Page {0} of a book and quill is longer than 1024 characters", i);
        }
    }

}
