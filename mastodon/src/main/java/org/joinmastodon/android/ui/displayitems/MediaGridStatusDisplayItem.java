package org.joinmastodon.android.ui.displayitems;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.BaseStatusListFragment;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.PhotoLayoutHelper;
import org.joinmastodon.android.ui.photoviewer.PhotoViewerHost;
import org.joinmastodon.android.ui.utils.MediaAttachmentViewController;
import org.joinmastodon.android.ui.views.FrameLayoutThatOnlyMeasuresFirstChild;
import org.joinmastodon.android.ui.views.MediaGridLayout;
import org.joinmastodon.android.utils.TypedObjectPool;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.CubicBezierInterpolator;

public class MediaGridStatusDisplayItem extends StatusDisplayItem{
	private static final String TAG="MediaGridDisplayItem";

	private final PhotoLayoutHelper.TiledLayoutResult tiledLayout;
	private final TypedObjectPool<GridItemType, MediaAttachmentViewController> viewPool;
	private final List<Attachment> attachments;
	private final ArrayList<ImageLoaderRequest> requests=new ArrayList<>();
	public final Status status;

	public MediaGridStatusDisplayItem(String parentID, BaseStatusListFragment<?> parentFragment, PhotoLayoutHelper.TiledLayoutResult tiledLayout, List<Attachment> attachments, Status status){
		super(parentID, parentFragment);
		this.tiledLayout=tiledLayout;
		this.viewPool=parentFragment.getAttachmentViewsPool();
		this.attachments=attachments;
		this.status=status;
		for(Attachment att:attachments){
			requests.add(new UrlImageLoaderRequest(switch(att.type){
				case IMAGE -> att.url;
				case VIDEO, GIFV -> att.previewUrl;
				default -> throw new IllegalStateException("Unexpected value: "+att.type);
			}, 1000, 1000));
		}
	}

	@Override
	public Type getType(){
		return Type.MEDIA_GRID;
	}

	@Override
	public int getImageCount(){
		return requests.size();
	}

	@Override
	public ImageLoaderRequest getImageRequest(int index){
		return requests.get(index);
	}

	public enum GridItemType{
		PHOTO,
		VIDEO,
		GIFV
	}

	public static class Holder extends StatusDisplayItem.Holder<MediaGridStatusDisplayItem> implements ImageLoaderViewHolder{
		private final FrameLayout wrapper;
		private final MediaGridLayout layout;
		private final View.OnClickListener clickListener=this::onViewClick, altTextClickListener=this::onAltTextClick;
		private final ArrayList<MediaAttachmentViewController> controllers=new ArrayList<>();

		private final FrameLayout altTextWrapper;
		private final TextView altTextButton;
		private final View altTextScroller;
		private final ImageButton altTextClose;
		private final TextView altText;

		private int altTextIndex=-1;
		private Animator altTextAnimator;

		public Holder(Activity activity, ViewGroup parent){
			super(new FrameLayoutThatOnlyMeasuresFirstChild(activity));
			wrapper=(FrameLayout)itemView;
			layout=new MediaGridLayout(activity);
			wrapper.addView(layout);

			activity.getLayoutInflater().inflate(R.layout.overlay_image_alt_text, wrapper);
			altTextWrapper=findViewById(R.id.alt_text_wrapper);
			altTextButton=findViewById(R.id.alt_button);
			altTextScroller=findViewById(R.id.alt_text_scroller);
			altTextClose=findViewById(R.id.alt_text_close);
			altText=findViewById(R.id.alt_text);
			altTextClose.setOnClickListener(this::onAltTextCloseClick);
		}

		@Override
		public void onBind(MediaGridStatusDisplayItem item){
			if(altTextAnimator!=null)
				altTextAnimator.cancel();

			layout.setTiledLayout(item.tiledLayout);
			for(MediaAttachmentViewController c:controllers){
				item.viewPool.reuse(c.type, c);
			}
			layout.removeAllViews();
			controllers.clear();
			int i=0;
			for(Attachment att:item.attachments){
				MediaAttachmentViewController c=item.viewPool.obtain(switch(att.type){
					case IMAGE -> GridItemType.PHOTO;
					case VIDEO -> GridItemType.VIDEO;
					case GIFV -> GridItemType.GIFV;
					default -> throw new IllegalStateException("Unexpected value: "+att.type);
				});
				if(c.view.getLayoutParams()==null)
					c.view.setLayoutParams(new MediaGridLayout.LayoutParams(item.tiledLayout.tiles[i]));
				else
					((MediaGridLayout.LayoutParams) c.view.getLayoutParams()).tile=item.tiledLayout.tiles[i];
				layout.addView(c.view);
				c.view.setOnClickListener(clickListener);
				c.view.setTag(i);
				if(c.altButton!=null){
					c.altButton.setOnClickListener(altTextClickListener);
					c.altButton.setTag(i);
					c.altButton.setAlpha(1f);
				}
				controllers.add(c);
				c.bind(att, item.status);
				i++;
			}
			altTextWrapper.setVisibility(View.GONE);
			altTextIndex=-1;
		}

		@Override
		public void setImage(int index, Drawable drawable){
			controllers.get(index).setImage(drawable);
		}

		@Override
		public void clearImage(int index){
			controllers.get(index).clearImage();
		}

		private void onViewClick(View v){
			int index=(Integer)v.getTag();
			if(!item.status.spoilerRevealed){
				item.parentFragment.onRevealSpoilerClick(this);
			}else if(item.parentFragment instanceof PhotoViewerHost){
				((PhotoViewerHost) item.parentFragment).openPhotoViewer(item.parentID, item.status, index, this);
			}
		}

		private void onAltTextClick(View v){
			if(altTextAnimator!=null)
				altTextAnimator.cancel();
			v.setVisibility(View.INVISIBLE);
			int index=(Integer)v.getTag();
			altTextIndex=index;
			Attachment att=item.attachments.get(index);
			altText.setText(att.description);
			altTextWrapper.setVisibility(View.VISIBLE);
			altTextWrapper.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
				@Override
				public boolean onPreDraw(){
					altTextWrapper.getViewTreeObserver().removeOnPreDrawListener(this);

					int[] loc={0, 0};
					v.getLocationInWindow(loc);
					int btnL=loc[0], btnT=loc[1];
					wrapper.getLocationInWindow(loc);
					btnL-=loc[0];
					btnT-=loc[1];

					ArrayList<Animator> anims=new ArrayList<>();
					anims.add(ObjectAnimator.ofFloat(altTextButton, View.ALPHA, 1, 0));
					anims.add(ObjectAnimator.ofFloat(altTextScroller, View.ALPHA, 0, 1));
					anims.add(ObjectAnimator.ofFloat(altTextClose, View.ALPHA, 0, 1));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "left", btnL, altTextWrapper.getLeft()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "top", btnT, altTextWrapper.getTop()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "right", btnL+v.getWidth(), altTextWrapper.getRight()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "bottom", btnT+v.getHeight(), altTextWrapper.getBottom()));
					for(Animator a:anims)
						a.setDuration(300);

					for(MediaAttachmentViewController c:controllers){
						if(c.altButton!=null && c.altButton!=v){
							anims.add(ObjectAnimator.ofFloat(c.altButton, View.ALPHA, 1, 0).setDuration(150));
						}
					}

					AnimatorSet set=new AnimatorSet();
					set.playTogether(anims);
					set.setInterpolator(CubicBezierInterpolator.DEFAULT);
					set.addListener(new AnimatorListenerAdapter(){
						@Override
						public void onAnimationEnd(Animator animation){
							altTextAnimator=null;
							for(MediaAttachmentViewController c:controllers){
								if(c.altButton!=null){
									c.altButton.setVisibility(View.INVISIBLE);
								}
							}
						}
					});
					altTextAnimator=set;
					set.start();

					return true;
				}
			});
		}

		private void onAltTextCloseClick(View v){
			if(altTextAnimator!=null)
				altTextAnimator.cancel();

			View btn=controllers.get(altTextIndex).altButton;
			for(MediaAttachmentViewController c:controllers){
				if(c.altButton!=null && c.altButton!=btn)
					 c.altButton.setVisibility(View.VISIBLE);
			}

			int[] loc={0, 0};
			btn.getLocationInWindow(loc);
			int btnL=loc[0], btnT=loc[1];
			wrapper.getLocationInWindow(loc);
			btnL-=loc[0];
			btnT-=loc[1];

			ArrayList<Animator> anims=new ArrayList<>();
			anims.add(ObjectAnimator.ofFloat(altTextButton, View.ALPHA, 1));
			anims.add(ObjectAnimator.ofFloat(altTextScroller, View.ALPHA, 0));
			anims.add(ObjectAnimator.ofFloat(altTextClose, View.ALPHA, 0));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "left", btnL));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "top", btnT));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "right", btnL+btn.getWidth()));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "bottom", btnT+btn.getHeight()));
			for(Animator a:anims)
				a.setDuration(300);

			for(MediaAttachmentViewController c:controllers){
				if(c.altButton!=null && c.altButton!=btn){
					anims.add(ObjectAnimator.ofFloat(c.altButton, View.ALPHA, 1).setDuration(150));
				}
			}

			AnimatorSet set=new AnimatorSet();
			set.playTogether(anims);
			set.setInterpolator(CubicBezierInterpolator.DEFAULT);
			set.addListener(new AnimatorListenerAdapter(){
				@Override
				public void onAnimationEnd(Animator animation){
					altTextAnimator=null;
					altTextWrapper.setVisibility(View.GONE);
					btn.setVisibility(View.VISIBLE);
				}
			});
			altTextAnimator=set;
			set.start();
		}

		public void setRevealed(boolean revealed){
			for(MediaAttachmentViewController c:controllers){
				c.setRevealed(revealed);
			}
		}

		public MediaAttachmentViewController getViewController(int index){
			return controllers.get(index);
		}

		public void setClipChildren(boolean clip){
			layout.setClipChildren(clip);
			wrapper.setClipChildren(clip);
		}
	}
}
