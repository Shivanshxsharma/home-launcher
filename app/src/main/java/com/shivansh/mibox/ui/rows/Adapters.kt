package com.shivansh.mibox.ui.rows

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.AppTile
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.data.UpNext
import com.shivansh.mibox.ui.AppArtSource
import com.shivansh.mibox.ui.CardSpec
import com.shivansh.mibox.ui.Cards
import com.shivansh.mibox.ui.FocusCard
import com.shivansh.mibox.ui.Images
import com.shivansh.mibox.ui.LinearScrim
import com.shivansh.mibox.ui.Palette
import com.shivansh.mibox.ui.RoundedImage
import com.shivansh.mibox.ui.Segments
import com.shivansh.mibox.ui.Ui
import com.shivansh.mibox.ui.showTitle

open class CardHolder(val card: FocusCard) : RecyclerView.ViewHolder(card) {
    val captionTitle: TextView = card.caption.findViewById(R.id.caption_title)
}

/** A row of [FocusCard]s. Lists are a handful of items, so updates simply rebind. */
abstract class CardAdapter<T, H : CardHolder>(protected val spec: CardSpec) : RecyclerView.Adapter<H>() {
    private var list: List<T> = emptyList()
    var items: List<T>
        get() = list

        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            list = value
            notifyDataSetChanged()
        }

    /** Sets [items] only if they changed, so the cards (and their focus) are otherwise left alone. */
    fun update(new: List<T>) {
        if (new != list) items = new
    }

    /** Moves one item, keeping its card (and its focus) as it goes. */
    fun move(from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices) return
        list = list.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    /** Swaps one item in place (e.g. once its full record arrives); the card keeps its focus. */
    fun replace(position: Int, item: T) {
        if (position !in list.indices) return
        list = list.toMutableList().apply { set(position, item) }
        notifyItemChanged(position)
    }

    /** OK pressed on a card. */
    var onActivate: ((item: T, card: View) -> Unit)? = null

    override fun getItemCount() = items.size

    protected fun card(parent: ViewGroup, body: Int, caption: Int) = FocusCard(parent.context, spec, body, caption)

    /** A show card, whose picture is its whole face. */
    protected fun showCard(parent: ViewGroup, body: Int, caption: Int) = card(parent, body, caption).apply { setFace(null) }

    protected fun <V : H> wire(holder: V): V {
        holder.card.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            holder.card.press()
            onActivate?.invoke(items[position], holder.card)
        }
        return holder
    }

    override fun onBindViewHolder(holder: H, position: Int) {
        holder.card.setLit(holder.card.isFocused, animate = false)
        bind(holder, items[position])
    }

    protected abstract fun bind(holder: H, item: T)
}

/** The dock of app icons. Faces carry no words; the name appears under the focused icon. */
class AppsAdapter(private val art: AppArtSource) : CardAdapter<AppTile, AppsAdapter.Holder>(Cards.APP) {
    class Holder(card: FocusCard) : CardHolder(card) {
        val banner: ImageView = card.body.findViewById(R.id.app_banner)
        val icon: ImageView = card.body.findViewById(R.id.app_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        wire(Holder(card(parent, R.layout.card_app, R.layout.caption_app)))

    override fun bind(holder: Holder, item: AppTile) {
        holder.card.contentDescription = item.label
        holder.captionTitle.text = item.label
        holder.banner.setImageDrawable(null)
        when (item.kind) {
            AppTile.Kind.APP -> {
                tinted(holder, Palette.INK)
                holder.icon.visibility = View.GONE
                art.load(holder.card, item.entry!!) { art, _ ->
                    if (art.isBanner) {
                        holder.banner.setImageDrawable(RoundedImage(art.bitmap, spec.radius))
                        holder.icon.visibility = View.GONE
                        holder.card.setFace(Palette.CARD)
                    } else {
                        holder.icon.setImageBitmap(art.bitmap)
                        holder.icon.setPadding(0, 0, 0, 0)
                        holder.icon.visibility = View.VISIBLE
                        tinted(holder, art.glow)
                    }
                    holder.card.setTint(art.glow)
                }
            }
            AppTile.Kind.SETTINGS -> glyph(holder, R.drawable.ic_settings)
            AppTile.Kind.ALL_APPS -> glyph(holder, R.drawable.ic_grid)
        }
    }

    /** A system tile: one large line glyph on glass. */
    private fun glyph(holder: Holder, icon: Int) {
        holder.card.setTag(R.id.load_key, null)
        holder.icon.setImageResource(icon)
        // Line glyphs sit smaller than app icons in the same box: 64px against 112px.
        val inset = Ui.px(24)
        holder.icon.setPadding(inset, inset, inset, inset)
        holder.icon.visibility = View.VISIBLE
        // The mockup's 10% white glass, flattened onto the dark band: opaque, so the shadow and
        // glow drawn behind the card can't show through the face.
        holder.card.setFace(GLASS_TILE)
        holder.card.setTint(Palette.INK)
    }

    /** A dark wash of the app's own color behind its icon. */
    private fun tinted(holder: Holder, glow: Int) {
        holder.card.setFace(
            LinearScrim(
                155f,
                intArrayOf(mix(glow, Palette.NIGHT, 0.72f), mix(glow, Palette.NIGHT, 0.92f)),
                floatArrayOf(0f, 1f),
                radius = spec.radius,
            ),
        )
        holder.card.setTint(glow)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val GLASS_TILE = 0xFF1C1C21.toInt()
    }
}

class UpNextAdapter(private val images: Images) : CardAdapter<UpNext, UpNextAdapter.Holder>(Cards.UP_NEXT) {
    class Holder(card: FocusCard) : CardHolder(card) {
        val image: ImageView = card.body.findViewById(R.id.card_image)
        val badge: TextView = card.body.findViewById(R.id.card_badge)
        val segments: Segments = card.body.findViewById(R.id.card_segments)
        val meta: TextView = card.caption.findViewById(R.id.caption_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        wire(Holder(showCard(parent, R.layout.card_up_next, R.layout.caption_up_next)))

    override fun bind(holder: Holder, item: UpNext) {
        val show = item.show
        images.card(holder.image, show.still, spec, Cards.UP_NEXT_FINISH)
        holder.badge.text = item.badge
        holder.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE
        holder.segments.set(item.watched, item.total)
        holder.captionTitle.text = show.title
        holder.meta.text = holder.meta.resources.getString(R.string.up_next_meta, item.next, item.note ?: show.service)
        holder.card.contentDescription = "${show.title}, ${item.next}"
        holder.card.setTint(show.tint)
    }
}

class PosterAdapter(private val images: Images, spec: CardSpec = Cards.POSTER) : CardAdapter<Show, PosterAdapter.Holder>(spec) {
    class Holder(card: FocusCard) : CardHolder(card) {
        val image: ImageView = card.body.findViewById(R.id.card_image)
        val title: TextView = card.body.findViewById(R.id.card_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        wire(Holder(showCard(parent, R.layout.card_poster, R.layout.caption_show)))

    override fun bind(holder: Holder, item: Show) {
        // Real posters carry their own title art: show them clean and name the show underneath.
        val keyArt = item.poster?.startsWith("https://") == true
        images.card(holder.image, item.poster ?: item.still, spec, if (keyArt) Cards.CLEAN_FINISH else Cards.POSTER_FINISH)
        holder.title.visibility = if (keyArt) View.GONE else View.VISIBLE
        holder.title.text = item.title
        holder.title.showTitle(item.style, 42f)
        holder.title.setLineHeight((holder.title.textSize * 0.95f).toInt())
        holder.captionTitle.text = when {
            !keyArt -> item.service
            item.service.isEmpty() -> item.title
            else -> "${item.title} · ${item.service}"
        }
        holder.card.contentDescription = item.title
        holder.card.setTint(item.tint)
    }
}

class FavoritesAdapter(private val images: Images) : CardAdapter<Show, FavoritesAdapter.Holder>(Cards.FAVORITE) {
    class Holder(card: FocusCard) : CardHolder(card) {
        val image: ImageView = card.body.findViewById(R.id.card_image)
        val title: TextView = card.body.findViewById(R.id.card_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        wire(Holder(showCard(parent, R.layout.card_favorite, R.layout.caption_show)))

    override fun bind(holder: Holder, item: Show) {
        images.card(holder.image, item.still, spec, Cards.FAVORITE_FINISH)
        holder.title.text = item.title
        holder.title.showTitle(item.style, 56f)
        holder.captionTitle.text = item.service
        holder.card.contentDescription = item.title
        holder.card.setTint(item.tint)
    }
}
