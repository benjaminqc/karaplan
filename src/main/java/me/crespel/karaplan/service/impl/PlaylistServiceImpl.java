package me.crespel.karaplan.service.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import me.crespel.karaplan.domain.Playlist;
import me.crespel.karaplan.domain.PlaylistComment;
import me.crespel.karaplan.domain.PlaylistSong;
import me.crespel.karaplan.domain.Song;
import me.crespel.karaplan.domain.User;
import me.crespel.karaplan.model.PlaylistSortDirection;
import me.crespel.karaplan.model.PlaylistSortType;
import me.crespel.karaplan.model.exception.BusinessException;
import me.crespel.karaplan.repository.PlaylistRepo;
import me.crespel.karaplan.repository.SongRepo;
import me.crespel.karaplan.repository.UserRepo;
import me.crespel.karaplan.service.PlaylistService;

@Service
public class PlaylistServiceImpl implements PlaylistService {

	@Autowired
	protected UserRepo userRepo;

	@Autowired
	protected PlaylistRepo playlistRepo;

	@Autowired
	protected SongRepo songRepo;

	@Override
	public Set<Playlist> findAll() {
		return Sets.newLinkedHashSet(playlistRepo.findAll());
	}

	@Override
	public Set<Playlist> findAll(Pageable pageable) {
		return Sets.newLinkedHashSet(playlistRepo.findAll(pageable));
	}

	@Override
	public Set<Playlist> findAll(Pageable pageable, User user) {
		return playlistRepo.findAllByMembersId(user.getId(), pageable).stream()
				.map(playlist -> {
					if (playlist.getReadOnly() == null) {
						playlist.setReadOnly(false);
					}
					return playlist;
				})
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	public Optional<Playlist> findById(Long id) {
		return findById(id, false);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Playlist> findById(Long id, boolean includeDetails) {
		return findById(id, includeDetails, null);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Playlist> findById(Long id, boolean includeDetails, User user) {
		return findById(id, includeDetails, null, null, null);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Playlist> findById(Long id, boolean includeDetails, User user, String accessKey, String filter) {
		Optional<Playlist> playlist = playlistRepo.findById(id);		
		if (playlist.isPresent()) {
			Playlist p = playlist.get();
			if (isMember(user, p)) {
				if (p.getReadOnly() == null) {
					p.setReadOnly(false);
				}
			} else if (accessKey != null) {
				if (p.getAccessKey() != null && !p.getAccessKey().equals(accessKey)) {
					throw new BusinessException("Invalid playlist access key");
				}
				p.setReadOnly(true);
			} else {
				throw new BusinessException("User " + user + " is not a member of playlist " + p);
			}
			if (includeDetails) {
				// Force eager load
				p.getSongs().size();
				p.getComments().size();
			}
		    if(filter != null) {
		      playlist = filterPlaylistSongs(p, filter);
		    }
		}
		return playlist;
	}

	@Override
	@Transactional
	public Playlist create(String name, User user) {
		Playlist playlist = new Playlist().setName(name);
		if (user != null) {
			playlist.getMembers().add(user);
		}
		return save(playlist, user);
	}

	@Override
	@Transactional
	public Playlist save(Playlist playlist) {
		if (playlist.getReadOnly() == null) {
			playlist.setReadOnly(false);
		}
		if (playlist.getAccessKey() == null) {
			playlist.setAccessKey(UUID.randomUUID().toString());
		}
		playlist.updateStats();
		return playlistRepo.save(playlist);
	}

	@Override
	@Transactional
	public Playlist save(Playlist playlist, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		}
		return save(playlist);
	}

	@Override
	@Transactional
	public Playlist addSong(Playlist playlist, Song song, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		} else if (Boolean.TRUE.equals(playlist.getReadOnly())) {
			throw new BusinessException("Playlist " + playlist + " is read-only");
		}

		// Import song if necessary 
		if (song.getId() == null) {
			song = songRepo.save(song);
		}

		// Calculate new position
		Integer position = playlist.getSongs().isEmpty() ? 0 : playlist.getSongs().last().getPosition();
		if (position != null) {
			position += 1;
		}

		// Add song
		PlaylistSong playlistSong = new PlaylistSong().setPlaylist(playlist).setSong(song).setPosition(position);
		playlist.getSongs().add(playlistSong);
		song.getPlaylists().add(playlistSong);
		song.updateStats();
		return save(playlist, user);
	}

	@Override
	@Transactional
	public Playlist removeSong(Playlist playlist, Song song, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		} else if (Boolean.TRUE.equals(playlist.getReadOnly())) {
			throw new BusinessException("Playlist " + playlist + " is read-only");
		}

		// Find and remove song
		PlaylistSong playlistSong = new PlaylistSong().setPlaylist(playlist).setSong(song);
		playlist.getSongs().remove(PlaylistSong.findInStream(playlist.getSongs().stream(), playlistSong));
		song.getPlaylists().remove(PlaylistSong.findInStream(song.getPlaylists().stream(), playlistSong));
		song.updateStats();

		// Assign new positions
		setSongPositions(playlist.getSongs());
		return save(playlist, user);
	}

	@Override
	@Transactional
	public Playlist addUser(Playlist playlist, User user, String accessKey) {
		if (playlist.getAccessKey() != null && playlist.getAccessKey().equals(accessKey)) {
			if (!playlist.getMembers().contains(user)) {
				playlist.getMembers().add(user);
				return save(playlist);
			}
		} else {
			throw new BusinessException("Invalid playlist access key");
		}
		return playlist;
	}

	@Override
	@Transactional
	public Playlist removeUser(Playlist playlist, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		}

		if (playlist.getMembers().contains(user)) {
			playlist.getMembers().remove(user);
			return save(playlist);
		}

		return playlist;
	}

	@Override
	@Transactional
	public Playlist addComment(Playlist playlist, User user, String comment) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		}

		playlist.getComments().add(new PlaylistComment()
				.setPlaylist(playlist)
				.setUser(user)
				.setComment(comment));
		return save(playlist);
	}

	@Override
	@Transactional
	public Playlist removeComment(Playlist playlist, long commentId) {
		return removeComment(playlist, null, commentId);
	}

	@Override
	@Transactional
	public Playlist removeComment(Playlist playlist, User user, long commentId) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		}

		playlist.getComments().removeIf(it -> it.getId() == commentId && (user == null || user.equals(it.getUser())));
		return save(playlist);
	}

	@Override
	public Playlist sort(Playlist playlist, PlaylistSortType sortType, PlaylistSortDirection sortDirection, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		} else if (Boolean.TRUE.equals(playlist.getReadOnly())) {
			throw new BusinessException("Playlist " + playlist + " is read-only");
		}

		// Sort songs
		List<PlaylistSong> sortedSongs = Lists.newArrayList(playlist.getSongs());
		switch (sortType) {
		case alpha:
			Collections.sort(sortedSongs, PlaylistSong.orderBySongNameComparator);
			break;
		case score:
			Collections.sort(sortedSongs, PlaylistSong.orderBySongScoreComparator);
			break;
		case dateAdded:
			Collections.sort(sortedSongs, PlaylistSong.orderByCreatedDateComparator);
			break;
		case random:
			Collections.shuffle(sortedSongs);
			break;
		default:
			throw new BusinessException("Invalid sort type " + sortType);
		}

		// Reverse order if necessary
		if (sortDirection == PlaylistSortDirection.desc) {
			Collections.reverse(sortedSongs);
		}

		// Assign new positions
		setSongPositions(sortedSongs);
		return save(playlist, user);
	}

	@Override
	@Transactional
	public void delete(Playlist playlist, User user) {
		if (!isMember(user, playlist)) {
			throw new BusinessException("User " + user + " is not a member of playlist " + playlist);
		}
		for (PlaylistSong playlistSong : playlist.getSongs()) {
			playlistSong.getSong().getPlaylists().remove(playlistSong);
			playlistSong.getSong().updateStats();
		}
		playlistRepo.delete(playlist);
	}

	@Override
	public boolean isMember(User user, Playlist playlist) {
		if (playlist == null) {
			return false;
		} else if (user == null) {
			return true;
		} else {
			return playlist.getMembers() != null && playlist.getMembers().contains(user);
		}
	}

	protected void setSongPositions(Collection<PlaylistSong> playlistSongs) {
		int pos = 1;
		for (PlaylistSong playlistSong : playlistSongs) {
			playlistSong.setPosition(pos++);
		}
	}
	
	private Optional<Playlist> filterPlaylistSongs(Playlist playlist, String filter) {
	  SortedSet<PlaylistSong> songs = Sets.newTreeSet();
	  playlist.getSongs().forEach(song -> {
	    if(song.getSong().getName().contains(filter) || (song.getSong().getArtist() != null && song.getSong().getArtist().getName().contains(filter))) {
	      songs.add(song);
	    }
	  });
	  playlist.setSongs(songs);
	  return Optional.of(playlist);
	}

}
