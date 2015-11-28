package com.yydcdut.note.mvp.p.home.impl;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;

import com.yydcdut.note.R;
import com.yydcdut.note.bean.Category;
import com.yydcdut.note.bean.PhotoNote;
import com.yydcdut.note.injector.ContextLife;
import com.yydcdut.note.model.CategoryDBModel;
import com.yydcdut.note.model.PhotoNoteDBModel;
import com.yydcdut.note.model.SandBoxDBModel;
import com.yydcdut.note.model.compare.ComparatorFactory;
import com.yydcdut.note.mvp.IView;
import com.yydcdut.note.mvp.p.home.IAlbumPresenter;
import com.yydcdut.note.mvp.v.home.IAlbumView;
import com.yydcdut.note.utils.FilePathUtils;
import com.yydcdut.note.utils.ImageManager.ImageLoaderManager;
import com.yydcdut.note.utils.LocalStorageUtils;
import com.yydcdut.note.utils.ThreadExecutorPool;
import com.yydcdut.note.utils.UiHelper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Inject;

/**
 * Created by yuyidong on 15/11/20.
 */
public class AlbumPresenterImpl implements IAlbumPresenter, Handler.Callback {
    private static final int MSG_UPDATE_DATA = 100;

    private IAlbumView mAlbumView;

    private List<PhotoNote> mPhotoNoteList;
    private int mCategoryId = -1;
    private int mAlbumSortKind;

    private Handler mHandler;

    private Context mContext;

    private LocalStorageUtils mLocalStorageUtils;
    private PhotoNoteDBModel mPhotoNoteDBModel;
    private SandBoxDBModel mSandBoxDBModel;
    private CategoryDBModel mCategoryDBModel;
    private ThreadExecutorPool mThreadExecutorPool;

    @Inject
    public AlbumPresenterImpl(@ContextLife("Activity") Context context, PhotoNoteDBModel photoNoteDBModel,
                              CategoryDBModel categoryDBModel, SandBoxDBModel sandBoxDBModel,
                              LocalStorageUtils localStorageUtils, ThreadExecutorPool threadExecutorPool) {

        mHandler = new Handler(this);
        mContext = context;
        mPhotoNoteDBModel = photoNoteDBModel;
        mCategoryDBModel = categoryDBModel;
        mSandBoxDBModel = sandBoxDBModel;
        mLocalStorageUtils = localStorageUtils;
        mThreadExecutorPool = threadExecutorPool;
        mAlbumSortKind = mLocalStorageUtils.getSortKind();
    }

    @Override
    public void attachView(IView iView) {
        mAlbumView = (IAlbumView) iView;
        mPhotoNoteList = mPhotoNoteDBModel.findByCategoryId(mCategoryId, mAlbumSortKind);
        mAlbumView.setAdapter(mPhotoNoteList);
    }

    @Override
    public void detachView() {

    }

    @Override
    public void bindData(int categoryId) {
        mCategoryId = categoryId;
    }

    @Override
    public void checkSandBox() {
        /**
         * 主要针对于拍完照回到这个界面之后判断沙盒里面还要数据没
         * 这里有延迟的原因是因为怕卡
         */
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mSandBoxDBModel.getAllNumber() > 0) {
                    mAlbumView.startSandBoxService();
                }
            }
        }, 3000);
    }

    @Override
    public void setAlbumSort(int sort) {
        mAlbumSortKind = sort;
        mLocalStorageUtils.setSortKind(mAlbumSortKind);
    }

    @Override
    public void saveAlbumSort() {
        mLocalStorageUtils.setSortKind(mAlbumSortKind);
    }

    @Override
    public int getAlbumSort() {
        return mAlbumSortKind;
    }

    @Override
    public void jump2DetailActivity(int position) {
        mAlbumView.jump2DetailActivity(mCategoryId, position, mAlbumSortKind);
    }

    @Override
    public void updateFromBroadcast(boolean broadcast_process, boolean broadcast_service, boolean broadcast_photo) {
        //当图片数据改变的时候，比如滤镜，Service作图
        //另外个进程发来广播的时候
        //todo  这里可以弄动画，需要计算的过程
        if (broadcast_process || broadcast_service) {
            mPhotoNoteList = mPhotoNoteDBModel.findByCategoryId(mCategoryId, mAlbumSortKind);
            mAlbumView.updateData(mPhotoNoteList);
        } else if (broadcast_photo) {
            mAlbumView.notifyDataSetChanged();
        }
    }

    @Override
    public void sortData() {
        Collections.sort(mPhotoNoteList, ComparatorFactory.get(mAlbumSortKind));
//        mAlbumView.updateData(mPhotoNoteList);
        mAlbumView.notifyDataSetChanged();
    }

    @Override
    public void changeCategoryWithPhotos(int categoryId) {
        mCategoryId = categoryId;
        mPhotoNoteList = mPhotoNoteDBModel.findByCategoryId(mCategoryId, mAlbumSortKind);
        mAlbumView.updateData(mPhotoNoteList);
    }

    @Override
    public void movePhotos2AnotherCategory() {
        List<Category> categoryList = mCategoryDBModel.findAll();
        final String[] categoryIdStringArray = new String[categoryList.size()];
        final String[] categoryLabelArray = new String[categoryList.size()];
        for (int i = 0; i < categoryIdStringArray.length; i++) {
            categoryIdStringArray[i] = categoryList.get(i).getId() + "";
            categoryLabelArray[i] = categoryList.get(i).getLabel();
        }
        mAlbumView.showMovePhotos2AnotherCategoryDialog(categoryIdStringArray, categoryLabelArray);
    }

    @Override
    public void changePhotosCategory(int toCategoryId) {
        if (mCategoryId != toCategoryId) {
            int changedNumber = doChangeCategory(toCategoryId);
            mPhotoNoteList = mPhotoNoteDBModel.findByCategoryLabelByForce(mCategoryId, mAlbumSortKind);
            mCategoryDBModel.updateChangeCategory(mCategoryId, toCategoryId, changedNumber);
        }
    }

    @Override
    public void deletePhotos() {
        //注意java.util.ConcurrentModificationException at java.util.ArrayList$ArrayListIterator.next(ArrayList.java:573)
        TreeMap<Integer, PhotoNote> map = new TreeMap<>(new Comparator<Integer>() {
            @Override
            public int compare(Integer lhs, Integer rhs) {
                return lhs - rhs;
            }
        });
        for (int i = 0; i < mPhotoNoteList.size(); i++) {
            PhotoNote photoNote = mPhotoNoteList.get(i);
            if (photoNote.isSelected()) {
                map.put(i, photoNote);
            }
        }
        int times = 0;
        for (Map.Entry<Integer, PhotoNote> entry : map.entrySet()) {
            mPhotoNoteDBModel.delete(entry.getValue());
            mPhotoNoteList.remove(entry.getValue());
            mAlbumView.notifyItemRemoved(entry.getKey() - times);
            times++;
        }
    }

    @Override
    public void createCategory(String newCategoryLabel) {
        int totalNumber = mCategoryDBModel.findAll().size();
        if (!TextUtils.isEmpty(newCategoryLabel)) {
            long id = mCategoryDBModel.saveCategory(newCategoryLabel, 0, totalNumber, true);
            if (id > 0) {
                Category category = mCategoryDBModel.findByCategoryId((int) id);
                mAlbumView.changeActivityListMenuCategoryChecked(category);
            } else {
                mAlbumView.showToast(mContext.getResources().getString(R.string.toast_fail));
            }
        } else {
            mAlbumView.showToast(mContext.getResources().getString(R.string.toast_fail));
        }
    }

    /**
     * @param toNewCategoryId
     * @return 变化了多少个
     */
    private int doChangeCategory(int toNewCategoryId) {
        TreeMap<Integer, PhotoNote> map = new TreeMap<>(new Comparator<Integer>() {
            @Override
            public int compare(Integer lhs, Integer rhs) {
                return lhs - rhs;
            }
        });
        for (int i = 0; i < mPhotoNoteList.size(); i++) {
            PhotoNote photoNote = mPhotoNoteList.get(i);
            if (photoNote.isSelected()) {
                photoNote.setSelected(false);
                photoNote.setCategoryId(toNewCategoryId);
                map.put(i, photoNote);
            }
        }
        int times = 0;
        int total = map.size();
        for (Map.Entry<Integer, PhotoNote> entry : map.entrySet()) {
            mPhotoNoteList.remove(entry.getValue());
            mAlbumView.notifyItemRemoved(entry.getKey() - times);
            if (times + 1 != total) {
                mPhotoNoteDBModel.update(entry.getValue(), false);
            } else {
                mPhotoNoteDBModel.update(entry.getValue(), true);
            }
            times++;
        }
        return map.size();
    }


    @Override
    public void savePhotoFromLocal(final Uri imageUri) {
        mAlbumView.showProgressBar();
        mThreadExecutorPool.getExecutorPool().execute(new Runnable() {
            @Override
            public void run() {
                PhotoNote photoNote = new PhotoNote(System.currentTimeMillis() + ".jpg", System.currentTimeMillis(),
                        System.currentTimeMillis(), "", "", System.currentTimeMillis(),
                        System.currentTimeMillis(), mCategoryId);
                ContentResolver cr = mContext.getContentResolver();
                //复制大图
                try {
                    FilePathUtils.copyFile(cr.openInputStream(imageUri), photoNote.getBigPhotoPathWithoutFile());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //保存小图
                FilePathUtils.saveSmallPhotoFromBigPhoto(photoNote);
                photoNote.setPaletteColor(UiHelper.getPaletteColor(ImageLoaderManager.loadImageSync(photoNote.getBigPhotoPathWithFile())));
                mPhotoNoteDBModel.save(photoNote);
                mHandler.sendEmptyMessage(MSG_UPDATE_DATA);
            }
        });
    }

    @Override
    public void savePhotoFromSystemCamera() {
        mAlbumView.showProgressBar();
        PhotoNote photoNote = new PhotoNote(System.currentTimeMillis() + ".jpg", System.currentTimeMillis(),
                System.currentTimeMillis(), "", "", System.currentTimeMillis(),
                System.currentTimeMillis(), mCategoryId);
        //复制大图
        try {
            FilePathUtils.copyFile(FilePathUtils.getTempFilePath(), photoNote.getBigPhotoPathWithoutFile());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //保存小图
        FilePathUtils.saveSmallPhotoFromBigPhoto(photoNote);
        photoNote.setPaletteColor(UiHelper.getPaletteColor(ImageLoaderManager.loadImageSync(photoNote.getBigPhotoPathWithFile())));
        mPhotoNoteDBModel.save(photoNote);
        mHandler.sendEmptyMessage(MSG_UPDATE_DATA);
    }

    @Override
    public void jump2Camera() {
        if (mLocalStorageUtils.getCameraSystem()) {
            mAlbumView.jump2CameraSystemActivity();
        } else {
            mAlbumView.jump2CameraActivity(mCategoryId);
        }
    }

    @Override
    public boolean checkStorageEnough() {
        if (!FilePathUtils.isSDCardStoredEnough()) {
            mAlbumView.showToast(mContext.getResources().getString(R.string.no_space));
            return false;
        }
        return true;
    }


    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_DATA:
                //因为是最新时间，即“图片创建事件”、“图片修改时间”、“笔记创建时间”、“笔记修改时间”，所以要么在最前面，要么在最后面//// TODO: 15/11/20 还是因时间来判断插入到哪里，所以要计算
                mPhotoNoteList = mPhotoNoteDBModel.findByCategoryId(mCategoryId, mAlbumSortKind);
                mAlbumView.updateData(mPhotoNoteList);
                switch (mAlbumSortKind) {
                    case ComparatorFactory.FACTORY_CREATE_CLOSE:
                    case ComparatorFactory.FACTORY_EDITED_CLOSE:
                        mAlbumView.notifyItemInserted(mPhotoNoteList.size() - 1);
                        break;
                    case ComparatorFactory.FACTORY_CREATE_FAR:
                    case ComparatorFactory.FACTORY_EDITED_FAR:
                        mAlbumView.notifyItemInserted(0);
                        break;
                }
                mAlbumView.hideProgressBar();
                break;
            default:
                break;
        }
        return true;
    }
}