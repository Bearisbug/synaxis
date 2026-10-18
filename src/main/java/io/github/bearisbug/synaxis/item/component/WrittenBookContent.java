package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.ThrowingFunction;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record WrittenBookContent(FilteredText<String> title, String author, int generation,
                                 List<FilteredText<Component>> pages, boolean resolved) {
    public static final WrittenBookContent EMPTY = new WrittenBookContent(new FilteredText<>("", null), "", 0, List.of(), true);

    public static final NetworkBuffer.Type<WrittenBookContent> NETWORK_TYPE = NetworkBufferTemplate.template(
            FilteredText.STRING_NETWORK_TYPE, WrittenBookContent::title,
            STRING, WrittenBookContent::author,
            VAR_INT, WrittenBookContent::generation,
            FilteredText.COMPONENT_NETWORK_TYPE.list(100), WrittenBookContent::pages,
            BOOLEAN, WrittenBookContent::resolved,
            WrittenBookContent::new);
    // The transform reports a value the constructor refuses as a decode error rather than throwing it out of decode
    public static final Codec<WrittenBookContent> CODEC = StructCodec.struct(
            "title", FilteredText.STRING_CODEC, WrittenBookContent::title,
            "author", Codec.STRING, WrittenBookContent::author,
            "generation", Codec.INT.optional(0), WrittenBookContent::generation,
            "pages", FilteredText.COMPONENT_CODEC.list(100).optional(List.of()), WrittenBookContent::pages,
            "resolved", Codec.BOOLEAN.optional(false), WrittenBookContent::resolved,
            WrittenBookContent::new).transform(ThrowingFunction.identity(), ThrowingFunction.identity());

    // The 26.2 client decodes a title of at most 32 characters and drops the connection over a longer one
    public WrittenBookContent {
        final String filteredTitle = title.filtered();
        Check.argCondition(title.text().length() > 32 || (filteredTitle != null && filteredTitle.length() > 32),
                "A written book title holds at most 32 characters, got: {0}", title.text());
        pages = List.copyOf(pages);
    }

    public WrittenBookContent(String title, String author, List<Component> pages) {
        this(title, author, 0, pages, true);
    }

    public WrittenBookContent(String title, String author, int generation, List<Component> pages, boolean resolved) {
        this(new FilteredText<>(title, null), author, generation, pages.stream().map(page -> new FilteredText<>(page, null)).toList(), resolved);
    }
}
