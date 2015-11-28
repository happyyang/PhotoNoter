package com.yydcdut.note.mvp.p.home.impl;

import android.os.Handler;
import android.os.Looper;

import com.yydcdut.note.bean.Category;
import com.yydcdut.note.bean.IUser;
import com.yydcdut.note.model.CategoryDBModel;
import com.yydcdut.note.model.PhotoNoteDBModel;
import com.yydcdut.note.model.UserCenter;
import com.yydcdut.note.model.observer.CategoryChangedObserver;
import com.yydcdut.note.model.observer.PhotoNoteChangedObserver;
import com.yydcdut.note.mvp.IView;
import com.yydcdut.note.mvp.p.home.IHomePresenter;
import com.yydcdut.note.mvp.v.home.IHomeView;

import java.util.List;

import javax.inject.Inject;

/**
 * Created by yuyidong on 15/11/19.
 */
public class HomePresenterImpl implements IHomePresenter, PhotoNoteChangedObserver, CategoryChangedObserver {
    private IHomeView mHomeView;
    private List<Category> mListData;
    /**
     * 当前的category的Id
     */
    private int mCategoryId = -1;

    private Handler mMainHandler;

    private CategoryDBModel mCategoryDBModel;
    private PhotoNoteDBModel mPhotoNoteDBModel;
    private UserCenter mUserCenter;

    @Inject
    public HomePresenterImpl(CategoryDBModel categoryDBModel, PhotoNoteDBModel photoNoteDBModel,
                             UserCenter userCenter) {
        mCategoryDBModel = categoryDBModel;
        mPhotoNoteDBModel = photoNoteDBModel;
        mUserCenter = userCenter;
    }

    @Override
    public void attachView(IView iView) {
        mHomeView = (IHomeView) iView;
        mListData = mCategoryDBModel.findAll();
        mMainHandler = new Handler(Looper.getMainLooper());
        mPhotoNoteDBModel.addObserver(this);
        mCategoryDBModel.addObserver(this);
    }

    @Override
    public void detachView() {
        mPhotoNoteDBModel.removeObserver(this);
        mCategoryDBModel.removeObserver(this);
    }

    public void setCategoryId(int categoryId) {
        mCategoryId = categoryId;
    }

    @Override
    public int getCategoryId() {
        return mCategoryId;
    }

    @Override
    public int getCheckCategoryPosition() {
        List<Category> categoryList = mCategoryDBModel.findAll();
        for (int i = 0; i < categoryList.size(); i++) {
            if (categoryList.get(i).isCheck()) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void setCheckedCategoryPosition(int position) {
        mCategoryDBModel.setCategoryMenuPosition(mListData.get(position));
        mHomeView.notifyCategoryDataChanged();
        mCategoryId = mListData.get(position).getId();
        mHomeView.changeFragment(mCategoryId);
    }

    @Override
    public void changeCategoryAfterSaving(Category category) {
        mCategoryDBModel.setCategoryMenuPosition(category);
        mListData = mCategoryDBModel.refresh();
        mHomeView.notifyCategoryDataChanged();
        mCategoryId = category.getId();
        mHomeView.changePhotos4Category(mCategoryId);
    }

    @Override
    public void setAdapter() {
        mHomeView.setCategoryList(mListData);
    }

    @Override
    public void drawerUserClick(int which) {
        switch (which) {
            case USER_ONE:
                if (mUserCenter.isLoginQQ()) {
                    mHomeView.jump2UserCenterActivity();
                } else {
                    mHomeView.jump2LoginActivity();
                }
                break;
            case USER_TWO:
                if (mUserCenter.isLoginEvernote()) {
                    mHomeView.jump2UserCenterActivity();
                } else {
                    mHomeView.jump2LoginActivity();
                }
                break;
        }
    }

    @Override
    public void drawerCloudClick() {
        mHomeView.cloudSyncAnimation();
    }

    @Override
    public void updateQQInfo() {
        if (mUserCenter.isLoginQQ()) {
            IUser qqUser = mUserCenter.getQQ();
            mHomeView.updateQQInfo(true, qqUser.getName(), qqUser.getImagePath());
        } else {
            mHomeView.updateQQInfo(false, null, null);
        }
    }

    @Override
    public void updateEvernoteInfo() {
        if (mUserCenter.isLoginEvernote()) {
            mHomeView.updateEvernoteInfo(true);
        } else {
            mHomeView.updateEvernoteInfo(false);
        }
    }

    @Override
    public void updateFromBroadcast(boolean broadcast_process, boolean broadcast_service) {
        //有时候categoryLabel为null，感觉原因是activity被回收了，但是一直解决不掉，所以迫不得已的解决办法
        if (mCategoryId == -1) {
            List<Category> categoryList = mCategoryDBModel.findAll();
            for (Category category : categoryList) {
                if (category.isCheck()) {
                    mCategoryId = category.getId();
                }
            }
        }

        //从另外个进程过来的数据
        if (broadcast_process) {
            int number = mPhotoNoteDBModel.findByCategoryLabelByForce(mCategoryId, -1).size();
            Category category = mCategoryDBModel.findByCategoryId(mCategoryId);
            category.setPhotosNumber(number);
            mCategoryDBModel.update(category);
            mHomeView.updateCategoryList(mCategoryDBModel.findAll());
        }

        //从Service中来
        if (broadcast_service) {
            mHomeView.updateCategoryList(mCategoryDBModel.findAll());
        }
    }

    @Override
    public void onUpdate(final int CRUD) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                switch (CRUD) {
                    case CategoryChangedObserver.OBSERVER_CATEGORY_DELETE:
                        mListData = mCategoryDBModel.findAll();
                        int beforeCategoryId = mCategoryId;
                        for (Category category : mListData) {
                            if (category.isCheck()) {
                                mCategoryId = category.getId();
                                break;
                            }
                        }
                        mHomeView.updateCategoryList(mCategoryDBModel.findAll());
                        if (mCategoryId != beforeCategoryId) {
                            mHomeView.changePhotos4Category(mCategoryId);
                        }
                        break;
                    case CategoryChangedObserver.OBSERVER_CATEGORY_MOVE:
                    case CategoryChangedObserver.OBSERVER_CATEGORY_CREATE:
                    case CategoryChangedObserver.OBSERVER_CATEGORY_RENAME:
                    case CategoryChangedObserver.OBSERVER_CATEGORY_SORT:
                        mHomeView.updateCategoryList(mCategoryDBModel.findAll());
                        break;
                }
            }
        });
    }

    @Override
    public void onUpdate(final int CRUD, int categoryId) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                switch (CRUD) {
                    case PhotoNoteChangedObserver.OBSERVER_PHOTONOTE_DELETE:
                    case PhotoNoteChangedObserver.OBSERVER_PHOTONOTE_CREATE:
                        int number = mPhotoNoteDBModel.findByCategoryId(mCategoryId, -1).size();
                        Category category = mCategoryDBModel.findByCategoryId(mCategoryId);
                        if (category.getPhotosNumber() != number) {
                            category.setPhotosNumber(number);
                            mCategoryDBModel.update(category);
                            mHomeView.updateCategoryList(mCategoryDBModel.findAll());
                        }
                        break;
                }
            }
        });
    }

}